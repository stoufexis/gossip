package com.stoufexis.swim.model

import zio.Chunk

import com.stoufexis.swim.model.Address.*
import com.stoufexis.swim.comms.*

sealed trait IncomingMessage derives Encoder:
  val typ:     MessageType
  val from:    RemoteAddress
  val to:      Address
  val payload: Chunk[Payload]

sealed trait OutgoingMessage derives Encoder:
  val typ:     MessageType
  val from:    Address
  val to:      RemoteAddress
  val payload: Chunk[Payload]

case class TerminatingMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      CurrentAddress,
  payload: Chunk[Payload]
) extends IncomingMessage derives Encoder

case class InitiatingMessage(
  typ:     MessageType,
  from:    CurrentAddress,
  to:      RemoteAddress,
  payload: Chunk[Payload]
) extends OutgoingMessage derives Encoder

case class RedirectMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      RemoteAddress,
  payload: Chunk[Payload]
) extends IncomingMessage, OutgoingMessage derives Encoder

object InitiatingMessage:
  def apply(
    typ:     MessageType,
    from:    CurrentAddress,
    to:      RemoteAddress,
    payload: Chunk[Payload] = Chunk()
  ): InitiatingMessage =
    new InitiatingMessage(typ, from, to, payload)
