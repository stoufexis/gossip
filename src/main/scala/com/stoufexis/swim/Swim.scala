package com.stoufexis.swim

import zio.*
import zio.stream.*

import com.stoufexis.swim.types.*

object Swim:
  import Message.*

  case class State(
    waitingOnAck:    Option[Address],
    members:         Set[Address],
    updates:         Updates,
    aggregatedTicks: Ticks
  )

  def apply(comms: Comms, cfg: SwimConfig) =
    def sendPing(st: State): Task[Address] =
      val membersArr: IndexedSeq[Address] =
        (st.members - cfg.address).toArray

      for
        index: Int <-
          ZIO.randomWith(_.nextIntBetween(0, membersArr.length))

        target: Address =
          membersArr(index)

        _ <-
          ZIO.logInfo(s"Pinging $target")

        _ <-
          comms.send(target, Ping(pinger = cfg.address, acker = target))
      yield target

    def sendIndirectPing(st: State, target: Address): Task[Unit] =
      val membersArr: IndexedSeq[Address] =
        (st.members - cfg.address - target).toArray

      for
        shuffledWithoutTarget: IndexedSeq[Address] <-
          ZIO.randomWith(_.shuffle(membersArr))

        indirectTargets: IndexedSeq[Address] =
          shuffledWithoutTarget.take(cfg.failureDetectionSubgroupSize)

        _ <-
          ZIO.logWarning(s"Pinging $target indirectly through $indirectTargets")

        _ <-
          ZIO.foreach(indirectTargets): via =>
            comms.send(via, Ping(pinger = cfg.address, acker = target))
      yield ()

    def handleMessages(st: State): Task[State] =
      def loop(remainder: Chunk[Message], acc: State): Task[State] =
        def tail = remainder.tail

        remainder.headOption match
          case None => ZIO.succeed(acc)

          // All indirect messages result in warnings, as they could indicate partial system failures

          case Some(Ping(pinger, acker)) if acker == cfg.address =>
            ZIO.logInfo(s"Acking ping from $pinger")
              *> comms.send(pinger, Ack(pinger = pinger, acker = acker))
              *> loop(tail, acc)

          case Some(p @ Ping(_, acker)) =>
            ZIO.logWarning(s"Redirecting ping to $acker")
              *> comms.send(acker, p)
              *> loop(tail, acc)

          case Some(Ack(pinger, acker)) if acc.waitingOnAck.exists(_ == acker) && pinger == cfg.address =>
            ZIO.logInfo(s"Received valid ack from $acker")
              *> loop(tail, acc.copy(waitingOnAck = None))

          case Some(Ack(pinger, acker)) if pinger == cfg.address =>
            ZIO.logWarning(s"Received unexpected ack from $acker")
              *> loop(tail, acc)

          case Some(a @ Ack(pinger, _)) =>
            ZIO.logWarning(s"Redirecting ack to $pinger")
              *> comms.send(pinger, a)
              *> loop(tail, acc)

      comms.receive.flatMap(loop(_, st))

    def handleExpirations(st: State, ticks: Ticks): Task[State] = st match
      case State(None, members, updates, aggTick) if aggTick + ticks > cfg.pingPeriodTicks =>
        sendPing(st).map: waitingOnAck =>
          State(Some(waitingOnAck), members, updates, Ticks.zero)

      case State(Some(waitingOnAck), members, updates, aggTick) if aggTick + ticks > cfg.pingPeriodTicks =>
        for
          _               <- ZIO.logWarning(s"Ping period expired while waiting for $waitingOnAck")
          newWaitingOnAck <- sendPing(st)
        yield State(
          Some(newWaitingOnAck),
          members - waitingOnAck,
          updates.failed(waitingOnAck),
          Ticks.zero
        )

      case State(Some(waitingOnAck), members, updates, aggTick) if aggTick + ticks > cfg.timeoutPeriodTicks =>
        for
          _ <- ZIO.logWarning(s"Direct ping period expired while waiting for $waitingOnAck")
          _ <- sendIndirectPing(st, waitingOnAck)
        yield State(Some(waitingOnAck), members, updates, aggTick + ticks)

      case State(waitingOnAck, members, updates, aggTick) =>
        ZIO.succeed(State(waitingOnAck, members, updates, aggTick + ticks))

    val initState: State =
      State(
        waitingOnAck    = None,
        members         = Set(cfg.address),
        updates         = Updates.joined(cfg.address),
        aggregatedTicks = Ticks.zero
      )

    ZStream
      .fromSchedule(Ticks.schedule(cfg.tickSpeed.toMillis))
      .runFoldZIO(initState)(handleExpirations(_, _).flatMap(handleMessages))
      *> ZIO.never

  end apply
