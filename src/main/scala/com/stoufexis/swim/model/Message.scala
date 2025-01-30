package com.stoufexis.swim.model

import zio.Chunk

import com.stoufexis.swim.model.Address.*
import com.stoufexis.swim.comms.*

sealed trait IncomingMessage derives Encoder:
  val typ:     MessageType
  val from:    RemoteAddress
  val to:      Address
  val payload: Chunk[Update]

sealed trait OutgoingMessage derives Encoder:
  val typ:     MessageType
  val from:    Address
  val to:      RemoteAddress
  val payload: Chunk[Update]

case class TerminatingMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      CurrentAddress,
  payload: Chunk[Update]
) extends IncomingMessage derives Encoder

case class InitiatingMessage(
  typ:     MessageType,
  from:    CurrentAddress,
  to:      RemoteAddress,
  payload: Chunk[Update]
) extends OutgoingMessage derives Encoder

case class RedirectMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      RemoteAddress,
  payload: Chunk[Update]
) extends IncomingMessage, OutgoingMessage derives Encoder

object InitiatingMessage:
  def apply(
    typ:     MessageType,
    from:    CurrentAddress,
    to:      RemoteAddress,
    payload: Chunk[Update] = Chunk()
  ): InitiatingMessage =
    new InitiatingMessage(typ, from, to, payload)
