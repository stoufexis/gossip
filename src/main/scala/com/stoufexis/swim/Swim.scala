package com.stoufexis.swim

import zio.*
import zio.prelude.fx.ZPure
import zio.stream.*

import com.stoufexis.swim.address.*
import com.stoufexis.swim.address.Address.*
import com.stoufexis.swim.comms.*
import com.stoufexis.swim.members.MemberState
import com.stoufexis.swim.message.*
import com.stoufexis.swim.pure.*
import com.stoufexis.swim.pure.Pure.*
import com.stoufexis.swim.tick.*
import com.stoufexis.swim.util.*

trait Swim:
  def members: UStream[Map[Address, MemberState]]

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
      ref:  SignallingRef[Map[Address, MemberState]],
      st:   State,
      rand: PseudoRandom,
      ts:   Ticks,
      join: Pure[Unit]
    ): Task[(State, PseudoRandom, Pure[Unit])] =
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

        _ <- ref.set(newState.members.map((add, st) => (add, st._1)))
        _ <- handleOutput(output)
      yield (newState, newRand, ZPure.unit)

    val initState = State(
      waitingOnAck       = None,
      members            = Map(),
      tick               = Ticks.zero,
      joiningVia         = None,
      disseminationLimit = cfg.disseminationLimit
    )

    def runLoop(rand: PseudoRandom, ref: SignallingRef[Map[Address, MemberState]]): Task[Unit] =
      ZStream
        .fromSchedule(Ticks.schedule(cfg.tickSpeed.toMillis))
        .runFoldZIO((initState, rand, sendJoin())):
          case ((s, r, join), t) => pipeline(ref, s, r, t, join)
        .unit

    for
      rand <- PseudoRandom.make
      ref  <- SignallingRef.make(Map.empty[Address, MemberState])
      _    <- runLoop(rand, ref).forkIn(scope)
    yield new:
      def members: UStream[Map[Address, MemberState]] = ref.updates
