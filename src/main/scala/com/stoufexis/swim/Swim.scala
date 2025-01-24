package com.stoufexis.swim

import zio.*
import zio.stream.*

import com.stoufexis.swim.address.*
import com.stoufexis.swim.util.*
import com.stoufexis.swim.tick.Ticks

object Swim:
  def run: RIO[Comms & SwimConfig, Nothing] =
    ZIO.service[Comms]
      .zip(ZIO.service[SwimConfig])
      .flatMap(runWithEnv(_, _))

  // TODO reduce overall duplication and move to seperate files maybe
  def runWithEnv(comms: Comms, cfg: SwimConfig): Task[Nothing] =
    import MessageType.*

    case class State(
      waitingOnAck: Option[RemoteAddress],
      members:      Members,
      tick:         Ticks,
      joiningVia:   Option[RemoteAddress]
    )

    def handleMessages(st: State): Task[State] =
      /** Note that when receiving a message that requires redirection a warning is logged, as it could
        * indicate partial system failures.
        */

      def loop(remainder: Chunk[IncomingMessage], acc: State): Task[State] =
        def tail = remainder.tail

        remainder.headOption match
          case None => ZIO.succeed(acc)

          case Some(msg) if !st.members.isOperational(msg.from) =>
            ZIO.logWarning(s"Dropping message from ${msg.from}, as it is not an operational member")
              *> loop(tail, acc)

          // messages for which a remote address is the receipient

          case Some(RedirectMessage(_, _, to, _)) if !st.members.isOperational(to) =>
            ZIO.logWarning(s"Dropping message aimed at $to, as it is not an operational member")
              *> loop(tail, acc)

          case Some(msg @ RedirectMessage(_, _, to, _)) =>
            ZIO.logWarning(s"Redirecting message to $to")
              *> comms.send(to, msg)
              *> loop(tail, acc)

          // messages we are the receipient for

          case Some(TerminatingMessage(Ping, from, _, _)) =>
            ZIO.logDebug(s"Acking ping from $from")
              *> comms.send(from, InitiatingMessage(Ack, from = cfg.address, to = from))
              *> loop(tail, acc)

          case Some(TerminatingMessage(Ack, from, _, _)) if acc.waitingOnAck.exists(_ == from) =>
            ZIO.logDebug(s"Received valid ack from $from")
              *> loop(tail, acc.copy(waitingOnAck = None))

          case Some(TerminatingMessage(Ack, from, _, _)) =>
            ZIO.logWarning(s"Received unexpected ack from $from")
              *> loop(tail, acc)

          case Some(TerminatingMessage(Join, from, _, _)) =>
            ZIO.logInfo(s"Node $from is joining the cluster")
              *> comms.send(from, InitiatingMessage(JoinAck, from = cfg.address, to = from))
              *> loop(tail, acc.copy(members = acc.members.setAlive(from)))

          case Some(TerminatingMessage(JoinAck, from, _, _)) if acc.joiningVia.exists(_ == from) =>
            ZIO.logInfo(s"Node $from confirmed our join")
              *> loop(tail, acc.copy(joiningVia = None))

          case Some(TerminatingMessage(JoinAck, from, _, _)) =>
            ZIO.logWarning(s"Received unexpected join ack from $from")
              *> loop(tail, acc)

      comms.receive.flatMap(loop(_, st))

    end handleMessages

    def sendPing(members: Members): Task[Option[RemoteAddress]] =
      ZIO.foreach(NonEmptySet(members.getOperational))(_.randomElem).tap:
        case Some(target) =>
          ZIO.logDebug(s"Pinging $target")
            *> comms.send(target, InitiatingMessage(Ping, from = cfg.address, to = target))

        case None =>
          ZIO.logInfo(s"No-one to ping")

    def sendIndirectPing(members: Members, target: RemoteAddress): Task[Unit] =
      NonEmptySet(members.getOperationalWithout(target)) match
        case Some(s) =>
          for
            indirectTargets <-
              s.randomElems(cfg.failureDetectionSubgroupSize)

            _ <-
              ZIO.logWarning(s"Pinging $target indirectly through $indirectTargets")

            _ <-
              ZIO.foreach(indirectTargets): via =>
                comms.send(via, InitiatingMessage(Ping, from = cfg.address, to = target))
          yield ()

        case None =>
          ZIO.logWarning(s"No indirect targets for $target")

    /** Send a join to one of the provided seed nodes, excluding the given tryExclude node. If after excluding
      * `tryExclude` the seedNodes are empty, the `tryExclude` address is contacted instead.
      */
    def sendJoin(
      seedNodes:  NonEmptySet[RemoteAddress],
      tryExclude: Option[RemoteAddress] = None
    ): Task[RemoteAddress] =
      for
        target: RemoteAddress <- tryExclude match
          case Some(e) => NonEmptySet(seedNodes - e).fold(ZIO.succeed(e))(_.randomElem)
          case None    => seedNodes.randomElem

        _ <- ZIO.logInfo(s"Joining via $target")
        _ <- comms.send(target, InitiatingMessage(Join, from = cfg.address, to = target))
      yield target

    def sendMessages(st: State, ticks: Ticks): Task[State] = (st.joiningVia, st.waitingOnAck) match
      // We are currently in the join process and the join timeout has been exceeded. Pick a different seed node
      case (Some(joiningVia), None) if st.tick + ticks > cfg.joinPeriodTicks =>
        sendJoin(cfg.seedNodes, Some(joiningVia)).map: newJoiningVia =>
          st.copy(tick = Ticks.zero, joiningVia = Some(newJoiningVia))

      // We are currently in the join process and the join timeout has not been exceeded
      case (Some(joiningVia), None) =>
        ZIO.succeed(st.copy(tick = st.tick + ticks, joiningVia = Some(joiningVia)))

      // We are not in the joining process anymore, we begin pinging after a ping period from our last join message

      // We are not waiting for any ack and a ping period passed. Ping another member.
      case (None, None) if st.tick + ticks > cfg.pingPeriodTicks =>
        sendPing(st.members).map: waitingOnAck =>
          st.copy(waitingOnAck = waitingOnAck, tick = Ticks.zero)

      // Ping period passed and we have not received an ack for the pinged member. They have failed.
      case (None, Some(waitingOnAck)) if st.tick + ticks > cfg.pingPeriodTicks =>
        for
          _               <- ZIO.logWarning(s"Ping period expired. Declaring $waitingOnAck as failed")
          newWaitingOnAck <- sendPing(st.members)
        yield st.copy(
          waitingOnAck = newWaitingOnAck,
          members      = st.members.setFailed(waitingOnAck),
          tick         = Ticks.zero
        )

      // Direct ping period passed and we have not received an ack for the pinged member. Ping indirectly.
      case (None, Some(waitingOnAck)) if st.tick + ticks > cfg.directPingPeriodTicks =>
        for
          _ <- ZIO.logWarning(s"Direct ping period expired for $waitingOnAck")
          _ <- sendIndirectPing(st.members, waitingOnAck)
        yield st.copy(tick = st.tick + ticks)

      // No period has expired yet, keep waiting for the ack or not waiting for any ack
      case (None, _) =>
        ZIO.succeed(st.copy(tick = st.tick + ticks))

      case (Some(_), Some(_)) =>
        ZIO.die(IllegalStateException("Joining and Pinging concurrently... Should be impossible!"))

    end sendMessages

    for
      joiningVia: RemoteAddress <-
        sendJoin(cfg.seedNodes)

      initState = State(
        waitingOnAck = None,
        members      = Members.empty,
        tick         = Ticks.zero,
        joiningVia   = Some(joiningVia)
      )

      _ <-
        ZStream
          .fromSchedule(Ticks.schedule(cfg.tickSpeed.toMillis))
          .runFoldZIO(initState): (st, ts) =>
            handleMessages(st).flatMap(sendMessages(_, ts))

      // We want to signal on the type level that this program will never terminate
      n: Nothing <- ZIO.never
    yield n

  end runWithEnv
