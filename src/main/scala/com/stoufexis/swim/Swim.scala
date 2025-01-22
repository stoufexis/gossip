package com.stoufexis.swim

import zio.*
import zio.stream.*

import com.stoufexis.swim.types.*

object Swim:
  import Message.*

  case class State(
    waitingOnAck:    Option[Address],
    members:         Members,
    aggregatedTicks: Ticks
  )

  def apply(comms: Comms, cfg: SwimConfig) =
    def sendPing(st: State): Task[Address] =
      val membersArr: IndexedSeq[Address] =
        st.members.getOperational

      for
        index: Int <-
          ZIO.randomWith(_.nextIntBetween(0, membersArr.length))

        target: Address =
          membersArr(index)

        _ <-
          ZIO.logInfo(s"Pinging $target")

        _ <-
          comms.send(target, Ping(from = cfg.address, to = target))
      yield target

    def sendIndirectPing(st: State, target: Address): Task[Unit] =
      val membersArr: IndexedSeq[Address] =
        st.members.getOperationalWithout(target)

      for
        shuffledWithoutTarget: IndexedSeq[Address] <-
          ZIO.randomWith(_.shuffle(membersArr))

        indirectTargets: IndexedSeq[Address] =
          shuffledWithoutTarget.take(cfg.failureDetectionSubgroupSize)

        _ <-
          ZIO.logWarning(s"Pinging $target indirectly through $indirectTargets")

        _ <-
          ZIO.foreach(indirectTargets): via =>
            comms.send(via, Ping(from = cfg.address, to = target))
      yield ()

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

          // handle messages directed to us
          case Some(Ping(from, to)) if to == cfg.address =>
            ZIO.logInfo(s"Acking ping from $from")
              *> comms.send(from, Ack(from = cfg.address, to = from))
              *> loop(tail, acc)

          case Some(Ack(from, to)) if to == cfg.address && acc.waitingOnAck.exists(_ == from) =>
            ZIO.logInfo(s"Received valid ack from $from")
              *> loop(tail, acc.copy(waitingOnAck = None))

          case Some(Ack(from, to)) if to == cfg.address =>
            ZIO.logWarning(s"Received unexpected ack from $from")
              *> loop(tail, acc)

          // redirect any message aiming at a dirrent node
          case Some(msg) =>
            ZIO.logWarning(s"Redirecting message to ${msg.to}")
              *> comms.send(msg.to, msg)
              *> loop(tail, acc)

      comms.receive.flatMap(loop(_, st))

    def handleExpirations(st: State, ticks: Ticks): Task[State] = st match
      case State(None, members, aggTick) if aggTick + ticks > cfg.pingPeriodTicks =>
        sendPing(st).map: waitingOnAck =>
          State(Some(waitingOnAck), members, Ticks.zero)

      case State(Some(waitingOnAck), members, aggTick) if aggTick + ticks > cfg.pingPeriodTicks =>
        for
          _               <- ZIO.logWarning(s"Ping period expired. Declaring $waitingOnAck as failed")
          newWaitingOnAck <- sendPing(st)
        yield State(
          Some(newWaitingOnAck),
          members.setFailed(waitingOnAck),
          Ticks.zero
        )

      case State(Some(waitingOnAck), members, aggTick) if aggTick + ticks > cfg.timeoutPeriodTicks =>
        for
          _ <- ZIO.logWarning(s"Direct ping period expired")
          _ <- sendIndirectPing(st, waitingOnAck)
        yield State(Some(waitingOnAck), members, aggTick + ticks)

      case State(waitingOnAck, members, aggTick) =>
        ZIO.succeed(State(waitingOnAck, members, aggTick + ticks))

    val initState: State =
      State(
        waitingOnAck    = None,
        members         = Members.current(cfg.address),
        aggregatedTicks = Ticks.zero
      )

    ZStream
      .fromSchedule(Ticks.schedule(cfg.tickSpeed.toMillis))
      .runFoldZIO(initState)((st, ts) => handleMessages(st).flatMap(handleExpirations(_, ts)))
      *> ZIO.never

  end apply
