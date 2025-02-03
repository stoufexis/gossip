package com.stoufexis.swim

import zio.logging.*
import zio.stream.*
import zio.{LogAnnotation as _, *}

import com.stoufexis.swim.comms.*
import com.stoufexis.swim.model.*
import com.stoufexis.swim.model.Address.*
import com.stoufexis.swim.programs.*
import com.stoufexis.swim.programs.Pure.*
import com.stoufexis.swim.tick.*
import com.stoufexis.swim.util.*

trait Swim:
  /** Emits the current memberlist and then subsequent updates.
    */
  def members: UStream[Map[Address, MemberStatus]]

object Swim:
  /** Initiates the swim runloop and returns a Swim object
    */
  val layer: URLayer[SwimConfig & Channel & Scope, Swim] = ZLayer:
    for
      cfg   <- ZIO.service[SwimConfig]
      chan  <- ZIO.service[Channel]
      scope <- ZIO.scope
      swim  <- init(cfg, chan, scope)
    yield swim

  object Annotations:
    val emitter:     LogAnnotation[Address]             = ???
    val messageType: LogAnnotation[Option[MessageType]] = ???
    val referrsTo:   LogAnnotation[Seq[Address]]        = ???

  def init(cfg: SwimConfig, chan: Channel, scope: Scope): UIO[Swim] =
    val emitter = Annotations.emitter(cfg.address)

    // lambda in a val instead of def taking bytes as a param since applying
    // the address is somewhat expensive and we want it to happen only once
    val decodeAll: Chunk[Byte] => Either[String, Chunk[IncomingMessage]] =
      Serde.decodeAll(cfg.address)

    def receiveMessages: Task[Chunk[IncomingMessage]] =
      chan.receive.flatMap: bs =>
        decodeAll(bs) match
          case Left(err) =>
            log(LogLevel.Error, message = s"Could not decode batch of messages because: $err") as Chunk()
          case Right(msgs) =>
            ZIO.succeed(msgs)

    def log(
      level:       LogLevel,
      referrsTo:   Seq[RemoteAddress]  = Seq(),
      messageType: Option[MessageType] = None,
      message:     => String
    ): UIO[Unit] =
      ZIO.logLevel(level)(ZIO.log(message))
        @@ emitter
        @@ Annotations.referrsTo(referrsTo)
        @@ Annotations.messageType(messageType)

    def handleOutput(messages: Chunk[Output]): Task[Unit] =
      ZIO.foreachDiscard(messages):
        case Output.Warn(referrsTo, msg) =>
          log(LogLevel.Warning, referrsTo, None, msg)

        case Output.Info(referrsTo, msg) =>
          log(LogLevel.Info, referrsTo, None, msg)

        case Output.Message(mt, target, bytes) =>
          log(LogLevel.Debug, Seq(target), Some(mt), "Sending message") *> chan.send(target, bytes)

    def pipeline(
      ref:   SignallingRef[Map[Address, MemberStatus]],
      state: State,
      rand:  PseudoRandom,
      ticks: Ticks
    ): Task[(State, PseudoRandom)] =
      for
        _ <-
          log(LogLevel.Debug, message = "Tick")

        _ <-
          ZIO.when(ticks.prev != Ticks.zero)(log(LogLevel.Warning, message = s"Missed ${ticks.prev} ticks"))

        messages: Chunk[IncomingMessage] <-
          receiveMessages

        iteration: Pure[Unit] =
          handleMessages(messages) *> handleTimeouts

        (output: Chunk[Output], newState: State, newRand: PseudoRandom) =
          singleIteration(cfg, ticks, state, rand, messages)

        _ <- ref.set(newState.members.map((add, st) => (add, st._1)))
        _ <- handleOutput(output)
        _ <- log(LogLevel.Debug, message = s"State Diff: ${state.diff(newState).print}")
      yield (newState, newRand)

    val initState = State(
      waitingOnAck = Process.Uninitialized,
      members      = Map(),
      joiningVia   = Process.Uninitialized,
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
