package com.stoufexis.swim.message

import com.stoufexis.swim.address.*

enum MessageType:
  case Ping, Ack, Join, JoinAck

sealed trait Message:
  val typ:  MessageType
  val from: Address
  val to:   Address

object Message:
  inline def apply(typ: MessageType, from: CurrentAddress, to: RemoteAddress): InitiatingMessage =
    InitiatingMessage(typ, from, to)

sealed trait IncomingMessage extends Message:
  val typ:  MessageType
  val from: RemoteAddress
  val to:   Address

sealed trait OutgoingMessage extends Message:
  val typ:  MessageType
  val from: Address
  val to:   RemoteAddress

case class TerminatingMessage(
  typ:  MessageType,
  from: RemoteAddress,
  to:   CurrentAddress
) extends IncomingMessage

case class InitiatingMessage(
  typ:  MessageType,
  from: CurrentAddress,
  to:   RemoteAddress
) extends OutgoingMessage

case class RedirectMessage(
  typ:  MessageType,
  from: RemoteAddress,
  to:   RemoteAddress
) extends IncomingMessage, OutgoingMessage