package com.stoufexis.swim

import zio.*
import zio.prelude.fx.ZPure
import zio.stream.*

import com.stoufexis.swim.address.*
import com.stoufexis.swim.address.Address.*
import com.stoufexis.swim.comms.*
import com.stoufexis.swim.message.*
import com.stoufexis.swim.tick.*
import com.stoufexis.swim.util.*

object Swim:
  import MessageType.*

  enum Level:
    case Info, Warn, Debug

    def log(msg: => String, annotations: Map[String, String]): UIO[Unit] =
      ZIO.logAnnotate(annotations.toSet.map(LogAnnotation(_, _))):
        this match
          case Level.Info  => ZIO.logInfo(msg)
          case Level.Warn  => ZIO.logWarning(msg)
          case Level.Debug => ZIO.logDebug(msg)

  enum Output:
    case Log(referrs: Option[IncomingMessage], level: Level, msg: String)
    case Message(typ: MessageType, target: RemoteAddress, bytes: Chunk[Byte])

  type Pure[A] = ZPure[Output, (State, PseudoRandom), (State, PseudoRandom), SwimConfig, Nothing, A]

  object Pure:
    def update(f: State => State): Pure[Unit] =
      ZPure.update((s, r) => (f(s), r))

    def setJoiningVia(jv: Option[RemoteAddress]): Pure[Unit] =
      update(_.setJoiningVia(jv))

    def setWaitingOnAck(jv: Option[RemoteAddress]): Pure[Unit] =
      update(_.setWaitingOnAck(jv))

    def setAlive(a: RemoteAddress): Pure[Unit] =
      update(_.setAlive(a))

    def setFailed(a: RemoteAddress): Pure[Unit] =
      update(_.setFailed(a))

    def append(chunk: Chunk[Payload]): Pure[Unit] =
      update(_.append(chunk))

    def appendAndGet(chunk: Chunk[Payload]): Pure[Chunk[Payload]] =
      for
        (st, r) <- ZPure.get[(State, PseudoRandom)]
        (out, newState) = st.appendAndGet(chunk)
        _ <- ZPure.set(newState, r)
      yield out

    def peek[A](f: State => A): Pure[A] =
      ZPure.get.map((s, _) => f(s))

    def get: Pure[State] =
      peek(identity)

    def updates: Pure[Chunk[Payload]] =
      peek(_.updates)

    def getOperational: Pure[Set[RemoteAddress]] =
      peek(_.getOperational)

    def getOperationalWithout(add: RemoteAddress): Pure[Set[RemoteAddress]] =
      peek(_.getOperationalWithout(add))

    def resetTicks: Pure[Unit] =
      update(_.resetTicks)

    def setTicks(ticks: Ticks): Pure[Unit] =
      update(_.setTicks(ticks))

    def addTicks(ticks: Ticks): Pure[Unit] =
      update(_.addTicks(ticks))

    def disseminated(diss: Set[RemoteAddress]): Pure[Unit] =
      update(_.disseminated(diss))

    def config: Pure[SwimConfig] =
      ZPure.service

    def warning(msg: String): Pure[Unit] =
      ZPure.log(Output.Log(None, Level.Warn, msg))

    def warning(referrs: IncomingMessage, msg: String): Pure[Unit] =
      ZPure.log(Output.Log(Some(referrs), Level.Warn, msg))

    def info(referrs: IncomingMessage, msg: String): Pure[Unit] =
      info(Some(referrs), msg)

    def info(msg: String): Pure[Unit] =
      info(None, msg)

    def info(referrs: Option[IncomingMessage], msg: String): Pure[Unit] =
      ZPure.log(Output.Log(referrs, Level.Info, msg))

    def debug(referrs: IncomingMessage, msg: String): Pure[Unit] =
      ZPure.log(Output.Log(Some(referrs), Level.Debug, msg))

    def randomElem[A](nes: NonEmptySet[A]): Pure[A] =
      ZPure.modify: (state, random) =>
        val (idx, random2) = random.nextInt(nes.size)
        (nes.drop(idx).head, (state, random2))

    def randomElems[A](nes: NonEmptySet[A], cnt: Int): Pure[Iterable[A]] =
      ZPure.modify: (state, random) =>
        val (iter, random2) = random.shuffle(nes)
        (iter.take(cnt), (state, random2))

  end Pure

  def sendMessageUnbounded(to: RemoteAddress, message: OutgoingMessage): Pure[Unit] =
    ZPure.log(Output.Message(message.typ, to, Serde.encodeUnbounded(message)))

  /** Backpressures if there is no room to output.
    */
  def sendMessage(to: Iterable[RemoteAddress], message: OutgoingMessage): Pure[Unit] =
    for
      cfg               <- Pure.config
      (included, bytes) <- ZPure.succeed(Serde.encodeBounded(message, cfg.maxTransmissionUnit))
      _                 <- ZPure.foreach(to)(to => ZPure.log(Output.Message(message.typ, to, bytes)))
      _                 <- Pure.disseminated(included)
    yield ()

  /** Note that when receiving a message that requires redirection a warning is logged, as it could indicate
    * partial system or networking failures.
    */
  def handleMessages(messages: Chunk[IncomingMessage]): Pure[Unit] =
    def loop(msg: IncomingMessage): Pure[Unit] = Pure.get.flatMap: st =>
      if !st.isOperational(msg.from) then
        Pure.warning(msg, "Dropping message as it is from a non-operational member")
      else
        st.joiningVia match
          // Behavior when we are in the join process
          case Some(joiningVia) =>
            // The only message we respond to is an expected joinAck directed at us
            msg match
              case TerminatingMessage(JoinAck, from, _, pl) if joiningVia == from =>
                Pure.info(msg, "Node confirmed out join")
                  *> Pure.setJoiningVia(None)
                  *> Pure.append(pl)

              case _ =>
                Pure.info(msg, "Dropping message as we are still joining")

          // Behavior when we are not in the join process
          case None =>
            msg match
              case RedirectMessage(_, _, to, _) if !st.isOperational(to) =>
                Pure.warning(msg, "Dropping message as the receiver is not an operational member")

              // We do not check if the message fits into the mtu limit, as the limit would have been applied by the sending node
              // We also do not include the addresses in the payload into our own counters, as they were not generated by us
              case msg @ RedirectMessage(_, _, to, pl) =>
                Pure.warning(msg, s"Redirecting message")
                  *> sendMessageUnbounded(to, msg)
                  *> Pure.append(pl)

              case TerminatingMessage(Ping, from, _, pl) =>
                for
                  cfg     <- Pure.config
                  _       <- Pure.debug(msg, "Acking ping")
                  updates <- Pure.appendAndGet(pl)
                  _       <- sendMessage(List(from), InitiatingMessage(Ack, cfg.address, from, updates))
                yield ()

              case TerminatingMessage(Ack, from, _, pl) if st.waitingOnAck.exists(_ == from) =>
                Pure.debug(msg, "Received valid ack")
                  *> Pure.setWaitingOnAck(None)
                  *> Pure.append(pl)

              // This message is likely old, so we dont accept its payload to guard against stale information
              case TerminatingMessage(Ack, _, _, _) =>
                Pure.warning(msg, "Dropping unexpected ack")

              case TerminatingMessage(Join, from, _, pl) =>
                for
                  cfg <- Pure.config
                  _   <- Pure.setAlive(from)
                  us  <- Pure.updates

                  _ <- Pure.info(msg, "Node is joining the cluster")
                  _ <- ZPure.when(pl.nonEmpty)(Pure.warning(msg, "Dropping Join payload"))

                  _ <- sendMessage(List(from), InitiatingMessage(JoinAck, cfg.address, from, us))
                yield ()

              // This message is likely old, so we dont accept its payload to guard against stale information
              case TerminatingMessage(JoinAck, _, _, _) =>
                Pure.warning(msg, "Received unexpected join ack")

    ZPure.foreachDiscard(messages)(loop)

  end handleMessages

  def pingRandomMember: Pure[Unit] =
    Pure.getOperational.map(NonEmptySet(_)).flatMap:
      case Some(addresses) =>
        for
          cfg    <- Pure.config
          target <- Pure.randomElem(addresses)
          us     <- Pure.updates
          _      <- Pure.setWaitingOnAck(Some(target))
          _      <- sendMessage(List(target), InitiatingMessage(Ping, cfg.address, target, us))
        yield ()

      case None =>
        Pure.info("No-one to ping") *> Pure.setWaitingOnAck(None)

  def pingIndirectly(target: RemoteAddress): Pure[Unit] =
    Pure.getOperationalWithout(target).map(NonEmptySet(_)).flatMap:
      case Some(s) =>
        for
          cfg     <- Pure.config
          us      <- Pure.updates
          targets <- Pure.randomElems(s, cfg.failureDetectionSubgroupSize)
          _       <- Pure.warning(s"Pinging $target indirectly through $targets")
          _       <- sendMessage(targets, InitiatingMessage(Ping, cfg.address, target, us))
        yield ()

      case None =>
        Pure.warning(s"No indirect targets for $target")

  /** Send a join to one of the provided seed nodes, excluding the given tryExclude node. If after excluding
    * `tryExclude` the seedNodes are empty, the `tryExclude` address is contacted instead.
    */
  def sendJoin(tryExclude: Option[RemoteAddress] = None): Pure[Unit] =
    for
      cfg <- Pure.config

      target: RemoteAddress <- tryExclude match
        case Some(e) => NonEmptySet(cfg.seedNodes - e).fold(ZPure.succeed(e))(Pure.randomElem)
        case None    => Pure.randomElem(cfg.seedNodes)

      _ <- Pure.info(s"Joining via $target")
      // There is no payload, so the bound does not apply
      _ <- sendMessageUnbounded(target, InitiatingMessage(Join, cfg.address, target, Chunk()))
      _ <- Pure.setJoiningVia(Some(target))
    yield ()

  def sendMessages(ticks: Ticks): Pure[Unit] =
    for
      st  <- Pure.get
      cfg <- Pure.config

      _ <- st.joiningVia match
        // We are currently in the join process and the join timeout has been exceeded. Pick a different seed node
        case Some(joiningVia) if st.tick + ticks > cfg.joinPeriodTicks =>
          sendJoin(tryExclude = Some(joiningVia)) *> Pure.resetTicks

        // We are currently in the join process and the join timeout has not been exceeded
        case Some(_) =>
          Pure.addTicks(ticks)

        // We are not in the joining process anymore, we begin pinging after a ping period from our last join message
        case None =>
          st.waitingOnAck match
            // We are not waiting for any ack and a ping period passed. Ping another member.
            case None if st.tick + ticks > cfg.pingPeriodTicks =>
              pingRandomMember *> Pure.resetTicks

            // Ping period passed and we have not received an ack for the pinged member. They have failed.
            case Some(waitingOnAck) if st.tick + ticks > cfg.pingPeriodTicks =>
              for
                _ <- Pure.warning(s"Ping period expired. Declaring $waitingOnAck as failed")
                _ <- Pure.setFailed(waitingOnAck)
                _ <- pingRandomMember
                _ <- Pure.resetTicks
              yield ()

            // Direct ping period passed and we have not received an ack for the pinged member. Ping indirectly.
            case Some(waitingOnAck) if st.tick + ticks > cfg.directPingPeriodTicks =>
              for
                _ <- Pure.warning(s"Direct ping period expired for $waitingOnAck")
                _ <- pingIndirectly(waitingOnAck)
                _ <- Pure.addTicks(ticks)
              yield ()

            // No period has expired yet, keep waiting for the ack or not waiting for any ack
            case _ => Pure.addTicks(ticks)
    yield ()

  end sendMessages

  def runloop: RIO[SwimConfig & Channel, Unit] =
    for
      cfg  <- ZIO.service[SwimConfig]
      chan <- ZIO.service[Channel]
      rand <- PseudoRandom.make

      initState = State(
        waitingOnAck       = None,
        members            = Map(),
        tick               = Ticks.zero,
        joiningVia         = None,
        disseminationLimit = cfg.disseminationLimit
      )

      _ <-
        // lambda in a val instead of def taking bytes as a param since applying
        // the address is somewhat expensive and we want it to happen only once
        val decodeAll: Chunk[Byte] => Either[String, Chunk[IncomingMessage]] =
          Serde.decodeAll(cfg.address)

        def receiveMessages: Task[Chunk[IncomingMessage]] =
          chan.receive.flatMap: bs =>
            decodeAll(bs) match
              case Left(err)   => ZIO.logError(s"Could not decode batch of messages because: $err") as Chunk()
              case Right(msgs) => ZIO.succeed(msgs)

        def handleOutput(messages: Chunk[Output]): RIO[Channel, Unit] =
          for
            chan <- ZIO.service[Channel]
            _ <-
              ZIO.foreachDiscard(messages):
                case Output.Log(Some(referrs), level, msg) =>
                  val annotations = Map(
                    "type" -> "RECEIVE",
                    "message_type" -> referrs.typ.toString,
                    "from" -> referrs.from.host,
                    "to" -> referrs.to.host
                  )
                  level.log(msg, annotations)

                case Output.Log(None, level, msg) =>
                  level.log(msg, Map("type" -> "GENERAL"))

                case Output.Message(mt, target, bytes) =>
                  val annotations = Map(
                    "type" -> "SEND",
                    "message_type" -> mt.toString,
                    "to" -> target.host
                  )
                  Level.Debug.log("Sending message", annotations) *> chan.send(target, bytes)
          yield ()

        def pipeline(
          st:   State,
          rand: PseudoRandom,
          ts:   Ticks,
          join: Pure[Unit]
        ): RIO[Channel, (State, PseudoRandom, Pure[Unit])] =
          for
            messages: Chunk[IncomingMessage] <-
              receiveMessages

            iteration: Pure[Unit] =
              join *> handleMessages(messages) *> sendMessages(ts)

            (output: Chunk[Output], result: Either[Nothing, ((State, PseudoRandom), Unit)]) =
              iteration.provideService(cfg).runAll((st, rand))

            // Either has Nothing on Left, so this is safe
            ((newState: State, newRand: PseudoRandom), _) =
              result.right.get

            _ <- handleOutput(output)
          yield (newState, newRand, ZPure.unit)

        ZStream
          .fromSchedule(Ticks.schedule(cfg.tickSpeed.toMillis))
          .runFoldZIO((initState, rand, sendJoin())):
            case ((s, r, join), t) => pipeline(s, r, t, join)
    yield ()
