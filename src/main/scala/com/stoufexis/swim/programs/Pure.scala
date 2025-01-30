package com.stoufexis.swim.programs

import zio.Chunk
import zio.prelude.fx.ZPure

import com.stoufexis.swim.*
import com.stoufexis.swim.model.*
import com.stoufexis.swim.model.Address.*
import com.stoufexis.swim.programs.Pure.*
import com.stoufexis.swim.tick.*
import com.stoufexis.swim.util.*

/** Allows the definition of the main gossip logic completely purely. On every tick of the runloop, incoming
  * messages are read from the buffer, the state is transitioned and a number of outputs (logs and messages)
  * are produced. Each of these iterations is modelled as a Pure program, which is interpreted and executed.
  *
  * Each output is modelled as an entry in the log and state is handled via the normal State parameter of
  * ZPure. In addition to the main state of the runloop, a pure Random number generator is provided.
  */
type Pure[A] = ZPure[Output, (State, PseudoRandom), (State, PseudoRandom), SwimConfig & Ticks, Nothing, A]

object Pure:
  enum Level:
    case Info, Warn, Debug

  enum Output:
    case Log(referrs: Option[IncomingMessage], level: Level, msg: String)
    case Message(typ: MessageType, target: RemoteAddress, bytes: Chunk[Byte])

  def update(f: State => State): Pure[Unit] =
    ZPure.update((s, r) => (f(s), r))

  def modify[A](f: State => (A, State)): Pure[A] =
    ZPure.modify: (s, r) =>
      val (a, s2) = f(s)
      (a, (s2, r))

  def setJoining(via: RemoteAddress): Pure[Unit] =
    ticks.flatMap(now => update(_.setJoining(via, now)))

  def clearJoining: Pure[Unit] =
    update(_.clearJoining)

  def setWaitingOnAck(waitingOn: RemoteAddress): Pure[Unit] =
    ticks.flatMap(now => update(_.setWaitingOnAck(waitingOn, now)))

  def clearWaitingOnAck: Pure[Unit] =
    update(_.clearWaitingOnAck)

  def setSuspicious(a: RemoteAddress): Pure[Unit] =
    ticks.flatMap(now => update(_.setSuspicious(a, now)))

  def append(chunk: Chunk[Update]): Pure[Unit] =
    ticks.flatMap(now => update(_.append(chunk, now)))

  def appendAndGet(chunk: Chunk[Update]): Pure[Chunk[Update]] =
    ZPure.modify: (st, r) =>
      val (out, newSt) = st.appendAndGet(chunk)
      (out, (newSt, r))

  def peek[A](f: State => A): Pure[A] =
    ZPure.get.map((s, _) => f(s))

  def get: Pure[State] =
    peek(identity)

  def inputs: Pure[(State, Ticks, SwimConfig)] =
    for
      state <- peek(identity)
      cfg   <- config
      ts    <- ticks
    yield (state, ts, cfg)

  def updates: Pure[Chunk[Update]] =
    config.flatMap(cfg => peek(_.updates(cfg.address, cfg.disseminationConstant)))

  def getOperational: Pure[Set[RemoteAddress]] =
    peek(_.getOperational)

  def getOperationalWithout(add: RemoteAddress): Pure[Set[RemoteAddress]] =
    peek(_.getOperationalWithout(add))

  def disseminated(diss: Set[Address]): Pure[Unit] =
    update(_.disseminated(diss))

  def config: Pure[SwimConfig] =
    ZPure.service[(State, PseudoRandom), SwimConfig]

  def ticks: Pure[Ticks] =
    ZPure.service[(State, PseudoRandom), Ticks]

  def warning(msg: String): Pure[Unit] =
    ZPure.log(Output.Log(None, Level.Warn, msg))

  def warning(referrs: IncomingMessage, msg: String): Pure[Unit] =
    ZPure.log(Output.Log(Some(referrs), Level.Warn, msg))

  def info(referrs: IncomingMessage, msg: String): Pure[Unit] =
    info(Some(referrs), msg)

  def info(msg: String): Pure[Unit] =
    info(None, msg)

  def info(referrs: Option[IncomingMessage], msg: String): Pure[Unit] =
    ZPure.log(Output.Log(referrs, Level.Info, msg))

  def debug(referrs: IncomingMessage, msg: String): Pure[Unit] =
    ZPure.log(Output.Log(Some(referrs), Level.Debug, msg))

  def randomElem[A](nes: NonEmptySet[A]): Pure[A] =
    ZPure.modify: (state, random) =>
      val (idx, random2) = random.nextInt(nes.size)
      (nes.drop(idx).head, (state, random2))

  def randomElems[A](nes: NonEmptySet[A], cnt: Int): Pure[Iterable[A]] =
    ZPure.modify: (state, random) =>
      val (iter, random2) = random.shuffle(nes)
      (iter.take(cnt), (state, random2))

  def message(typ: MessageType, target: RemoteAddress, bytes: Chunk[Byte]): Pure[Unit] =
    ZPure.log(Output.Message(typ, target, bytes))
