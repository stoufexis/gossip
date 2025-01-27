package com.stoufexis.swim.comms

import zio.*

import com.stoufexis.swim.SwimConfig
import com.stoufexis.swim.address.*
import com.stoufexis.swim.address.Address.*
import com.stoufexis.swim.message.*
import com.stoufexis.swim.message.MessageType.*
import com.stoufexis.swim.util.*
import com.stoufexis.swim.State

/** Using a class directly here as I do not forsee wanting to mock this service. The channel would probably be
  * more valuable to mock.
  */
class Comms(chan: Channel, cfg: SwimConfig):
  // lambda in a val instead of def taking bytes as a param since applying
  // the address is somewhat expensive and we want it to happen only once
  private val decodeAll: Chunk[Byte] => Either[String, Chunk[IncomingMessage]] =
    Serde.decodeAll(cfg.address)

  def receiveMessage: Task[Chunk[IncomingMessage]] =
    chan.receive.flatMap: bs =>
      decodeAll(bs) match
        case Left(err)   => ZIO.fail(RuntimeException(err))
        case Right(msgs) => ZIO.succeed(msgs)

  def sendMessageUnbounded(to: RemoteAddress, message: OutgoingMessage): Task[Unit] =
    chan.send(to, Serde.encodeUnbounded(message))

  /** Backpressures if there is no room to output.
    */
  def sendMessage(to: Iterable[RemoteAddress], message: OutgoingMessage): Task[Set[RemoteAddress]] =
    val (included, bytes) = Serde.encodeBounded(message, cfg.maxTransmissionUnit)
    ZIO.foreach(to)(chan.send(_, bytes)) as included

  def sendMessage(to: RemoteAddress, message: OutgoingMessage): Task[Set[RemoteAddress]] =
    sendMessage(List(to), message)

  def pingRandomMember(state: State): Task[Option[(RemoteAddress, Set[RemoteAddress])]] =
    ZIO.foreach(NonEmptySet(state.getOperational))(_.randomElem).flatMap:
      case Some(target) =>
        for
          _        <- ZIO.logDebug(s"Pinging $target")
          included <- sendMessage(target, InitiatingMessage(Ping, cfg.address, target, state.updates))
        yield Some(target, included)

      case None =>
        ZIO.logInfo(s"No-one to ping") as None

  def pingIndirectly(target: RemoteAddress, state: State): Task[Option[Set[RemoteAddress]]] =
    NonEmptySet(state.getOperationalWithout(target)) match
      case Some(s) =>
        for
          targets  <- s.randomElems(cfg.failureDetectionSubgroupSize)
          _        <- ZIO.logWarning(s"Pinging $target indirectly through $targets")
          included <- sendMessage(targets, InitiatingMessage(Ping, cfg.address, target, state.updates))
        yield Some(included)

      case None =>
        ZIO.logWarning(s"No indirect targets for $target") as None

  /** Send a join to one of the provided seed nodes, excluding the given tryExclude node. If after excluding
    * `tryExclude` the seedNodes are empty, the `tryExclude` address is contacted instead.
    */
  def sendJoin(tryExclude: Option[RemoteAddress] = None): Task[RemoteAddress] =
    for
      target: RemoteAddress <- tryExclude match
        case Some(e) => NonEmptySet(cfg.seedNodes - e).fold(ZIO.succeed(e))(_.randomElem)
        case None    => cfg.seedNodes.randomElem

      _ <- ZIO.logInfo(s"Joining via $target")
      // There is no payload, so the bound does not apply
      _ <- sendMessageUnbounded(target, InitiatingMessage(Join, from = cfg.address, to = target))
    yield target

object Comms:
  val layer: URLayer[Channel & SwimConfig, Comms] =
    ZLayer.fromFunction(Comms(_, _))
