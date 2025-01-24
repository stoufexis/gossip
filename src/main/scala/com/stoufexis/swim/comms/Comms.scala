package com.stoufexis.swim.comms

import zio.*

import com.stoufexis.swim.SwimConfig
import com.stoufexis.swim.address.*
import com.stoufexis.swim.members.*
import com.stoufexis.swim.message.*
import com.stoufexis.swim.message.MessageType.*
import com.stoufexis.swim.util.*

/**
  * Using a class directly here as I do not forsee wanting to mock
  * this service. The channel would probably be more valuable to mock.
  */
class Comms(chan: Channel, cfg: SwimConfig):
  def receiveMessage: Task[Chunk[IncomingMessage]] = ???

  /** Backpressures if there is no room to output.
    */
  def sendMessage(to: Address, message: OutgoingMessage): Task[Unit] = ???

  def pingRandomMember(members: Members): Task[Option[RemoteAddress]] =
    ZIO.foreach(NonEmptySet(members.getOperational))(_.randomElem).tap:
      case Some(target) =>
        ZIO.logDebug(s"Pinging $target")
          *> sendMessage(target, InitiatingMessage(Ping, from = cfg.address, to = target))

      case None =>
        ZIO.logInfo(s"No-one to ping")

  def pingIndirectly(target: RemoteAddress, members: Members): Task[Unit] =
    NonEmptySet(members.getOperationalWithout(target)) match
      case Some(s) =>
        for
          targets <- s.randomElems(cfg.failureDetectionSubgroupSize)
          _       <- ZIO.logWarning(s"Pinging $target indirectly through $targets")

          _ <-
            ZIO.foreach(targets): via =>
              sendMessage(via, InitiatingMessage(Ping, from = cfg.address, to = target))
        yield ()

      case None =>
        ZIO.logWarning(s"No indirect targets for $target")

  /** Send a join to one of the provided seed nodes, excluding the given tryExclude node. If after excluding
    * `tryExclude` the seedNodes are empty, the `tryExclude` address is contacted instead.
    */
  def sendJoin(tryExclude: Option[RemoteAddress] = None): Task[RemoteAddress] =
    for
      target: RemoteAddress <- tryExclude match
        case Some(e) => NonEmptySet(cfg.seedNodes - e).fold(ZIO.succeed(e))(_.randomElem)
        case None    => cfg.seedNodes.randomElem

      _ <- ZIO.logInfo(s"Joining via $target")
      _ <- sendMessage(target, InitiatingMessage(Join, from = cfg.address, to = target))
    yield target

object Comms:
  val layer: URLayer[Channel & SwimConfig, Comms] =
    ZLayer.fromFunction(Comms(_, _))