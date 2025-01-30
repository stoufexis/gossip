package com.stoufexis.swim

import zio.*
import zio.stream.*

import com.stoufexis.swim.comms.*
import com.stoufexis.swim.model.*
import com.stoufexis.swim.model.Address.*
import com.stoufexis.swim.programs.*
import com.stoufexis.swim.programs.Pure.*
import com.stoufexis.swim.tick.*
import com.stoufexis.swim.util.*

trait Swim:
  def members: UStream[Map[Address, MemberStatus]]

object Swim:
  val layer: URLayer[SwimConfig & Channel & Scope, Swim] = ZLayer:
    for
      cfg   <- ZIO.service[SwimConfig]
      chan  <- ZIO.service[Channel]
      scope <- ZIO.scope
      swim  <- init(cfg, chan, scope)
    yield swim

  def init(cfg: SwimConfig, chan: Channel, scope: Scope): UIO[Swim] =
    // lambda in a val instead of def taking bytes as a param since applying
    // the address is somewhat expensive and we want it to happen only once
    val decodeAll: Chunk[Byte] => Either[String, Chunk[IncomingMessage]] =
      Serde.decodeAll(cfg.address)

    def receiveMessages: Task[Chunk[IncomingMessage]] =
      chan.receive.flatMap: bs =>
        decodeAll(bs) match
          case Left(err)   => ZIO.logError(s"Could not decode batch of messages because: $err") as Chunk()
          case Right(msgs) => ZIO.succeed(msgs)

    def log(level: Level, msg: => String, annotations: Map[String, String]): UIO[Unit] =
      ZIO.logAnnotate(annotations.toSet.map(LogAnnotation(_, _))):
        level match
          case Level.Info  => ZIO.logInfo(msg)
          case Level.Warn  => ZIO.logWarning(msg)
          case Level.Debug => ZIO.logDebug(msg)

    def handleOutput(messages: Chunk[Output]): Task[Unit] =
      ZIO.foreachDiscard(messages):
        case Output.Log(Some(referrs), level, msg) =>
          val annotations = Map(
            "type" -> "RECEIVE",
            "message_type" -> referrs.typ.toString,
            "from" -> referrs.from.host,
            "to" -> referrs.to.host
          )
          log(level, msg, annotations)

        case Output.Log(None, level, msg) =>
          log(level, msg, Map("type" -> "GENERAL"))

        case Output.Message(mt, target, bytes) =>
          val annotations = Map(
            "type" -> "SEND",
            "message_type" -> mt.toString,
            "to" -> target.host
          )
          log(Level.Debug, "Sending message", annotations) *> chan.send(target, bytes)

    def pipeline(
      ref:  SignallingRef[Map[Address, MemberStatus]],
      st:   State,
      rand: PseudoRandom,
      ts:   Ticks
    ): Task[(State, PseudoRandom)] =
      for
        _ <- ZIO.logDebug("Tick")
        _ <- ZIO.whenDiscard(ts.prev != Ticks.zero)(ZIO.logWarning(s"Missed ${ts.prev} ticks"))

        messages: Chunk[IncomingMessage] <-
          receiveMessages

        iteration: Pure[Unit] =
          handleMessages(messages) *> handleTimeouts

        (output: Chunk[Output], newState: State, newRand: PseudoRandom) =
          singleIteration(cfg, ts, st, rand, messages)

        _ <- ref.set(newState.members.map((add, st) => (add, st._1)))
        _ <- handleOutput(output)
      yield (newState, newRand)

    val initState = State(
      waitingOnAck = Waiting.NeverWaited,
      members      = Map(),
      joiningVia   = Waiting.NeverWaited,
      currentInfo  = MemberInfo(MemberStatus.Alive, 0, 0, Ticks.zero)
    )

    def runLoop(rand: PseudoRandom, ref: SignallingRef[Map[Address, MemberStatus]]): Task[Unit] =
      ZStream
        .fromSchedule(Ticks.schedule(cfg.tickSpeed.toMillis))
        .runFoldZIO((initState, rand)):
          case ((s, r), t) => pipeline(ref, s, r, t)
        .unit

    for
      rand <- PseudoRandom.make
      ref  <- SignallingRef.make(Map.empty[Address, MemberStatus])
      _    <- runLoop(rand, ref).forkIn(scope)
    yield new:
      def members: UStream[Map[Address, MemberStatus]] = ref.updates
