package com.stoufexis.swim

import zio.*
import zio.stream.*

import java.time.LocalDateTime

object Swim:
  import Message.*, Update.*

  case class State(
    waitingOnAck: Option[Address],
    periodStart:  LocalDateTime,
    members:      Vector[Address],
    updates:      Vector[Update]
  )

  object State:
    def empty(periodStart: LocalDateTime): State = ???

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

    def periodExpired(lastPeriodStart: LocalDateTime, time: LocalDateTime): Boolean =
      time.isAfter(lastPeriodStart.plus(cfg.pingPeriod))

    def timeoutExpired(lastPeriodStart: LocalDateTime, time: LocalDateTime): Boolean =
      time.isAfter(lastPeriodStart.plus(cfg.timeoutPeriod))

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

    def process(st: State, time: LocalDateTime): Task[State] = st match
      case State(None, lastPeriodStart, members, updates) if periodExpired(lastPeriodStart, time) =>
        for
          waitingOnAck <- sendPing(st)
          waitingOnAck <- handleMessages(Some(waitingOnAck))
        yield State(waitingOnAck, time, members, updates)

      case State(Some(waitingOnAck), periodStart, members, updates) if periodExpired(periodStart, time) =>
        for
          _               <- ZIO.logWarning(s"Ping period expired while waiting for $waitingOnAck")
          newWaitingOnAck <- sendPing(st)
          newWaitingOnAck <- handleMessages(Some(newWaitingOnAck))
        yield State(
          newWaitingOnAck,
          time,
          // TODO: removing from members list should have constant complexity!
          members.filterNot(_ == waitingOnAck),
          Failed(waitingOnAck) +: updates
        )

      case State(Some(waitingOnAck), periodStart, members, updates) if timeoutExpired(periodStart, time) =>
        for
          _            <- ZIO.logWarning(s"Direct ping period expired while waiting for $waitingOnAck")
          _            <- sendIndirectPing(st, waitingOnAck)
          waitingOnAck <- handleMessages(Some(waitingOnAck))
        yield State(waitingOnAck, periodStart, members, updates)

      case State(waitingOnAck, periodStart, members, updates) =>
        for
          waitingOnAck <- handleMessages(waitingOnAck)
        yield State(waitingOnAck, periodStart, members, updates)

    val ticker: ZStream[Any, Nothing, LocalDateTime] =
      ZStream
        .tick(cfg.tickSpeed)
        .mapZIO(_ => ZIO.clockWith(_.localDateTime))
        
    ticker
      .take(1)
      .mapZIO(t => ticker.drop(1).runFoldZIO(State.empty(t))(process(_, _)))
      .runDrain *> ZIO.never

  end apply