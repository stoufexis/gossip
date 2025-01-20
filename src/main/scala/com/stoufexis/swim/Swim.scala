package com.stoufexis.swim

import zio.*
import zio.stream.*

import com.stoufexis.swim.types.*

object Swim:
  import Message.*
  import Update.*

  case class State(
    waitingOnAck:    Option[Address],
    members:         Vector[Address],
    updates:         Vector[Update],
    aggregatedTicks: Ticks
  )

  object State:
    def empty: State = ???

  def apply(comms: Comms, cfg: SwimConfig) =
    def sendPing(st: State): Task[Address] =
      for
        index <- ZIO.randomWith(_.nextIntBetween(0, st.members.length))
        target = st.members(index)
        _ <- ZIO.logInfo(s"Pinging $target")
        _ <- comms.send(target, Ping(pinger = cfg.address, acker = target))
      yield target

    def sendIndirectPing(st: State, target: Address): Task[Unit] =
      for
        shuffledWithoutTarget: Vector[Address] <-
          ZIO.randomWith(_.shuffle(st.members.filterNot(_ == target)))

        indirectTargets: Vector[Address] =
          shuffledWithoutTarget.take(cfg.failureDetectionSubgroupSize)

        _ <-
          ZIO.logWarning(s"Pinging $target indirectly through $indirectTargets")

        _ <-
          ZIO.foreach(indirectTargets): via =>
            comms.send(via, Ping(pinger = cfg.address, acker = target))
      yield ()

    def handleMessages(waitingOn: Option[Address]): Task[Option[Address]] =
      def loop(remainder: Chunk[Message], acc: Option[Address]): Task[Option[Address]] =
        remainder.headOption match
          case None =>
            ZIO.succeed(acc)

          // All indirect messages result in warnings, as they could indicate partial system failures

          case Some(Ping(pinger, acker)) if acker == cfg.address =>
            ZIO.logInfo(s"Acking ping from $pinger")
              *> comms.send(pinger, Ack(pinger = pinger, acker = acker))
              *> loop(remainder.tail, acc)

          case Some(p @ Ping(_, acker)) =>
            ZIO.logWarning(s"Redirecting ping to $acker")
              *> comms.send(acker, p)
              *> loop(remainder.tail, acc)

          case Some(Ack(pinger, acker)) if waitingOn.exists(_ == acc) && pinger == cfg.address =>
            ZIO.logInfo(s"Received valid ack from $acker")
              *> loop(remainder.tail, None)

          case Some(Ack(pinger, acker)) if pinger == cfg.address =>
            ZIO.logWarning(s"Received unexpected ack from $acker")
              *> loop(remainder.tail, acc)

          case Some(a @ Ack(pinger, _)) =>
            ZIO.logWarning(s"Redirecting ack to $pinger")
              *> comms.send(pinger, a)
              *> loop(remainder.tail, acc)

      comms.receive.flatMap(loop(_, waitingOn))

    def process(st: State, ticks: Ticks): Task[State] = st match
      case State(None, members, updates, aggTick) if aggTick + ticks > cfg.pingPeriodTicks =>
        for
          waitingOnAck <- sendPing(st)
          waitingOnAck <- handleMessages(Some(waitingOnAck))
        yield State(waitingOnAck, members, updates, Ticks.zero)

      case State(Some(waitingOnAck), members, updates, aggTick) if aggTick + ticks > cfg.pingPeriodTicks =>
        for
          _               <- ZIO.logWarning(s"Ping period expired while waiting for $waitingOnAck")
          newWaitingOnAck <- sendPing(st)
          newWaitingOnAck <- handleMessages(Some(newWaitingOnAck))
        yield State(
          newWaitingOnAck,
          // TODO: removing from members list should have constant complexity!
          members.filterNot(_ == waitingOnAck),
          Failed(waitingOnAck) +: updates,
          Ticks.zero
        )

      case State(Some(waitingOnAck), members, updates, aggTick) if aggTick + ticks > cfg.timeoutPeriodTicks =>
        for
          _            <- ZIO.logWarning(s"Direct ping period expired while waiting for $waitingOnAck")
          _            <- sendIndirectPing(st, waitingOnAck)
          waitingOnAck <- handleMessages(Some(waitingOnAck))
        yield State(waitingOnAck, members, updates, aggTick + ticks)

      case State(waitingOnAck, members, updates, aggTick) =>
        for
          waitingOnAck <- handleMessages(waitingOnAck)
        yield State(waitingOnAck, members, updates, aggTick + ticks)

    ZStream
      .fromSchedule(Ticks.schedule(cfg.tickSpeed.toMillis))
      .runFoldZIO(State.empty)(process(_, _)) *> ZIO.never

  end apply
