package com.stoufexis.swim.message

import zio.Chunk

import com.stoufexis.swim.address.*

sealed trait IncomingMessage:
  val typ:     MessageType
  val from:    RemoteAddress
  val to:      Address
  val payload: Chunk[Payload]

sealed trait OutgoingMessage:
  val typ:     MessageType
  val from:    Address
  val to:      RemoteAddress
  val payload: Chunk[Payload]

case class TerminatingMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      CurrentAddress,
  payload: Chunk[Payload]
) extends IncomingMessage

case class InitiatingMessage(
  typ:     MessageType,
  from:    CurrentAddress,
  to:      RemoteAddress,
  payload: Chunk[Payload]
) extends OutgoingMessage

object InitiatingMessage:
  inline def apply(typ: MessageType, from: CurrentAddress, to: RemoteAddress): InitiatingMessage =
    InitiatingMessage(typ, from, to, Chunk())

case class RedirectMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      RemoteAddress,
  payload: Chunk[Payload]
) extends IncomingMessage, OutgoingMessage
