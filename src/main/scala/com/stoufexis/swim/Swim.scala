package com.stoufexis.swim

import zio.*
import zio.stream.*

import com.stoufexis.swim.types.*

object Swim:
  import Message.*

  case class State(
    waitingOnAck: Option[Address],
    members:      Members,
    tick:         Ticks,
    joiningVia:   Option[Address]
  ):
    def joining: Boolean = joiningVia.isDefined

  // TODO reduce overall duplication and move to seperate files maybe
  def apply(comms: Comms, cfg: SwimConfig) =
    extension [A](v: Vector[A])
      def randomElement: UIO[A] =
        ZIO.randomWith(_.nextIntBetween(0, v.length).map(v(_)))

      def randomElements(take: Int): UIO[Vector[A]] =
        ZIO.randomWith(_.shuffle(v).map(_.take(take)))

    def handleMessages(st: State): Task[State] =
      /** Note that when receiving a message that requires redirection a warning is logged, as it could
        * indicate partial system failures.
        */
      def loop(remainder: Chunk[Message], acc: State): Task[State] =
        def tail = remainder.tail

        remainder.headOption match
          case None => ZIO.succeed(acc)

          // Drop messages from non-members or failed members
          case Some(msg) if !st.members.isOperational(msg.from) =>
            ZIO.logWarning(s"Dropping message from ${msg.from}, as it is not an operational member")
              *> loop(tail, acc)

          // correctness measure, drop and trace messages where from == to, as they are invalid/corrupt
          case Some(msg) if msg.from == msg.to =>
            ZIO.logError(s"Dropping loopback message with address ${msg.from}")
              *> loop(tail, acc)

          // handle messages directed to us
          case Some(Ping(from, to)) if to == cfg.address =>
            ZIO.logDebug(s"Acking ping from $from")
              *> comms.send(from, Ack(from = cfg.address, to = from))
              *> loop(tail, acc)

          case Some(Ack(from, to)) if to == cfg.address && acc.waitingOnAck.exists(_ == from) =>
            ZIO.logDebug(s"Received valid ack from $from")
              *> loop(tail, acc.copy(waitingOnAck = None))

          case Some(Ack(from, to)) if to == cfg.address =>
            ZIO.logWarning(s"Received unexpected ack from $from")
              *> loop(tail, acc)

          case Some(Join(from, to)) if to == cfg.address =>
            ZIO.logInfo(s"Node $from is joining the cluster")
              *> comms.send(from, JoinAck(from = cfg.address, to = from))
              *> loop(tail, acc.copy(members = acc.members.setAlive(from)))

          case Some(JoinAck(from, to)) if to == cfg.address && acc.joiningVia.exists(_ == from) =>
            ZIO.logInfo(s"Node $from confirmed our join")
              *> loop(tail, acc.copy(joiningVia = None))

          case Some(JoinAck(from, to)) if to == cfg.address =>
            ZIO.logWarning(s"Received unexpected join ack from $from")
              *> loop(tail, acc)

          // redirect any message aiming at a different node if the different node is operational
          case Some(msg) if st.members.isOperational(msg.to) =>
            ZIO.logWarning(s"Redirecting message to ${msg.to}")
              *> comms.send(msg.to, msg)
              *> loop(tail, acc)

          case Some(msg) =>
            ZIO.logWarning(s"Will not redirect message from ${msg.from} to non operational member ${msg.to}")
              *> loop(tail, acc)

      comms.receive.flatMap(loop(_, st))

    end handleMessages

    def sendPing(members: Members): Task[Address] =
      for
        target <- members.getOperational.randomElement
        _      <- ZIO.logDebug(s"Pinging $target")
        _      <- comms.send(target, Ping(from = cfg.address, to = target))
      yield target

    def sendIndirectPing(members: Members, target: Address): Task[Unit] =
      for
        indirectTargets <-
          members
            .getOperationalWithout(target)
            .randomElements(cfg.failureDetectionSubgroupSize)

        _ <-
          ZIO.logWarning(s"Pinging $target indirectly through $indirectTargets")

        _ <-
          ZIO.foreach(indirectTargets): via =>
            comms.send(via, Ping(from = cfg.address, to = target))
      yield ()

    def sendJoin(seedNodes: Set[Address]): Task[Address] =
      for
        target <- seedNodes.toVector.randomElement
        _      <- ZIO.logInfo(s"Joining via $target")
        _      <- comms.send(target, Join(from = cfg.address, to = target))
      yield target

    def sendMessages(st: State, ticks: Ticks, seedNodes: Set[Address]): Task[State] =
      st.joiningVia match
        // We are currently in the join process and the join timeout has been exceeded. Pick a different seed node
        case Some(joiningVia) if st.tick + ticks > cfg.joinPeriodTicks =>
          sendJoin(seedNodes - joiningVia).map: newJoiningVia =>
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

    // Do not send a join to ourselves if we are a seed node
    val seedNodesWithoutCurrent: Set[Address] =
      cfg.seedNodes - cfg.address

    for
      joiningVia: Address <-
        sendJoin(seedNodesWithoutCurrent)

      initState = State(
        waitingOnAck = None,
        members      = Members.current(cfg.address),
        tick         = Ticks.zero,
        joiningVia   = Some(joiningVia)
      )

      _ <-
        ZStream
          .fromSchedule(Ticks.schedule(cfg.tickSpeed.toMillis))
          .runFoldZIO(initState): (st, ts) =>
            handleMessages(st).flatMap(sendMessages(_, ts, seedNodesWithoutCurrent))

      // We want to signal on the type level that this program will never terminate
      n: Nothing <- ZIO.never
    yield n

  end apply