package com.stoufexis.swim

import zio.*
import zio.stream.*

import com.stoufexis.swim.address.*

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

    extension [A](v: Vector[A])
      def randomElement: UIO[A] =
        ZIO.randomWith(_.nextIntBetween(0, v.length).map(v(_)))

      def randomElements(take: Int): UIO[Vector[A]] =
        ZIO.randomWith(_.shuffle(v).map(_.take(take)))

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

          case Some(RedirectMessage(_, _, to)) if !st.members.isOperational(to) =>
            ZIO.logWarning(s"Dropping message aimed at $to, as it is not an operational member")
              *> loop(tail, acc)

          case Some(msg @ RedirectMessage(_, _, to)) =>
            ZIO.logWarning(s"Redirecting message to $to")
              *> comms.send(to, msg)
              *> loop(tail, acc)

          // messages we are the receipient for

          case Some(TerminatingMessage(Ping, from, _)) =>
            ZIO.logDebug(s"Acking ping from $from")
              *> comms.send(from, Message(Ping, from = cfg.address, to = from))
              *> loop(tail, acc)

          case Some(TerminatingMessage(Ack, from, _)) if acc.waitingOnAck.exists(_ == from) =>
            ZIO.logDebug(s"Received valid ack from $from")
              *> loop(tail, acc.copy(waitingOnAck = None))

          case Some(TerminatingMessage(Ack, from, _)) =>
            ZIO.logWarning(s"Received unexpected ack from $from")
              *> loop(tail, acc)

          case Some(TerminatingMessage(Join, from, _)) =>
            ZIO.logInfo(s"Node $from is joining the cluster")
              *> comms.send(from, Message(JoinAck, from = cfg.address, to = from))
              *> loop(tail, acc.copy(members = acc.members.setAlive(from)))

          case Some(TerminatingMessage(JoinAck, from, _)) if acc.joiningVia.exists(_ == from) =>
            ZIO.logInfo(s"Node $from confirmed our join")
              *> loop(tail, acc.copy(joiningVia = None))

          case Some(TerminatingMessage(JoinAck, from, _)) =>
            ZIO.logWarning(s"Received unexpected join ack from $from")
              *> loop(tail, acc)
      comms.receive.flatMap(loop(_, st))

    end handleMessages

    def sendPing(members: Members): Task[RemoteAddress] =
      for
        target <- members.getOperational.randomElement
        _      <- ZIO.logDebug(s"Pinging $target")
        _      <- comms.send(target, Message(Ping, from = cfg.address, to = target))
      yield target

    def sendIndirectPing(members: Members, target: RemoteAddress): Task[Unit] =
      for
        indirectTargets <-
          members
            .getOperationalWithout(target)
            .randomElements(cfg.failureDetectionSubgroupSize)

        _ <-
          ZIO.logWarning(s"Pinging $target indirectly through $indirectTargets")

        _ <-
          ZIO.foreach(indirectTargets): via =>
            comms.send(via, Message(Ping, from = cfg.address, to = target))
      yield ()

    def sendJoin(seedNodes: Set[RemoteAddress]): Task[RemoteAddress] =
      for
        target <- seedNodes.toVector.randomElement
        _      <- ZIO.logInfo(s"Joining via $target")
        _      <- comms.send(target, Message(Join, from = cfg.address, to = target))
      yield target

    def sendMessages(st: State, ticks: Ticks): Task[State] =
      st.joiningVia match
        // We are currently in the join process and the join timeout has been exceeded. Pick a different seed node
        case Some(joiningVia) if st.tick + ticks > cfg.joinPeriodTicks =>
          sendJoin(cfg.seedNodes - joiningVia).map: newJoiningVia =>
            State(
              // this must be a None anyway, so overwrite it here instead of copying it from the state
              waitingOnAck = None,
              members      = st.members,
              tick         = Ticks.zero,
              joiningVia   = Some(newJoiningVia)
            )

        // We are currently in the join process and the join timeout has not been exceeded
        case Some(joiningVia) =>
          ZIO.succeed:
            State(
              waitingOnAck = None,
              members      = st.members,
              tick         = st.tick + ticks,
              joiningVia   = Some(joiningVia)
            )

        // We are not in the joining process anymore, we begin pinging after a ping period from our last join message
        case None =>
          st.waitingOnAck match
            // We are not waiting for any ack and a ping period passed. Ping another member.
            case None if st.tick + ticks > cfg.pingPeriodTicks =>
              sendPing(st.members).map: waitingOnAck =>
                State(
                  waitingOnAck = Some(waitingOnAck),
                  members      = st.members,
                  tick         = Ticks.zero,
                  joiningVia   = None
                )

            // Ping period passed and we have not received an ack for the pinged member. They have failed.
            case Some(waitingOnAck) if st.tick + ticks > cfg.pingPeriodTicks =>
              for
                _               <- ZIO.logWarning(s"Ping period expired. Declaring $waitingOnAck as failed")
                newWaitingOnAck <- sendPing(st.members)
              yield State(
                waitingOnAck = Some(newWaitingOnAck),
                members      = st.members.setFailed(waitingOnAck),
                tick         = Ticks.zero,
                joiningVia   = None
              )

            // Direct ping period passed and we have not received an ack for the pinged member. Ping indirectly.
            case Some(waitingOnAck) if st.tick + ticks > cfg.directPingPeriodTicks =>
              for
                _ <- ZIO.logWarning(s"Direct ping period expired for $waitingOnAck")
                _ <- sendIndirectPing(st.members, waitingOnAck)
              yield State(
                waitingOnAck = Some(waitingOnAck),
                members      = st.members,
                tick         = st.tick + ticks,
                joiningVia   = None
              )

            // No period has expired yet, keep waiting for the ack or not waiting for any ack
            case waitingOnAck =>
              ZIO.succeed:
                State(
                  waitingOnAck = waitingOnAck,
                  members      = st.members,
                  tick         = st.tick + ticks,
                  joiningVia   = None
                )

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
