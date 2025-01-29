package com.stoufexis.swim.pure

import zio.Chunk
import zio.prelude.fx.ZPure

import com.stoufexis.swim.*
import com.stoufexis.swim.address.*
import com.stoufexis.swim.address.Address.*
import com.stoufexis.swim.message.*
import com.stoufexis.swim.pure.Pure.*
import com.stoufexis.swim.tick.*
import com.stoufexis.swim.util.*

/** Allows the definition of the main gossip logic completely purely. On every tick of the runloop, incoming
  * messages are read from the buffer, the state is transitioned and a number of outputs (logs and messages)
  * are produced. Each of these iterations is modelled via a series of Pure programs, which are compiled and
  * executed.
  *
  * Each output is modelled as an entry in the log and state is handled via the norma State parameter of
  * ZPure. In addition to the main state of the runloop, a pure Random number generator is provided.
  */
type Pure[A] = ZPure[Output, (State, PseudoRandom), (State, PseudoRandom), SwimConfig, Nothing, A]

object Pure:
  enum Level:
    case Info, Warn, Debug

  enum Output:
    case Log(referrs: Option[IncomingMessage], level: Level, msg: String)
    case Message(typ: MessageType, target: RemoteAddress, bytes: Chunk[Byte])

  def update(f: State => State): Pure[Unit] =
    ZPure.update((s, r) => (f(s), r))

  def setJoiningVia(jv: Option[RemoteAddress]): Pure[Unit] =
    update(_.setJoiningVia(jv))

  def setWaitingOnAck(jv: Option[RemoteAddress]): Pure[Unit] =
    update(_.setWaitingOnAck(jv))

  def setAlive(a: RemoteAddress): Pure[Unit] =
    update(_.setAlive(a))

  def setFailed(a: RemoteAddress): Pure[Unit] =
    update(_.setFailed(a))

  def append(chunk: Chunk[Payload]): Pure[Unit] =
    update(_.append(chunk))

  def appendAndGet(chunk: Chunk[Payload]): Pure[Chunk[Payload]] =
    for
      (st, r) <- ZPure.get[(State, PseudoRandom)]
      (out, newState) = st.appendAndGet(chunk)
      _ <- ZPure.set(newState, r)
    yield out

  def peek[A](f: State => A): Pure[A] =
    ZPure.get.map((s, _) => f(s))

  def get: Pure[State] =
    peek(identity)

  def updates: Pure[Chunk[Payload]] =
    peek(_.updates)

  def getOperational: Pure[Set[RemoteAddress]] =
    peek(_.getOperational)

  def getOperationalWithout(add: RemoteAddress): Pure[Set[RemoteAddress]] =
    peek(_.getOperationalWithout(add))

  def resetTicks: Pure[Unit] =
    update(_.resetTicks)

  def setTicks(ticks: Ticks): Pure[Unit] =
    update(_.setTicks(ticks))

  def addTicks(ticks: Ticks): Pure[Unit] =
    update(_.addTicks(ticks))

  def disseminated(diss: Set[RemoteAddress]): Pure[Unit] =
    update(_.disseminated(diss))

  def config: Pure[SwimConfig] =
    ZPure.service

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
