package com.stoufexis.swim

import com.stoufexis.swim.address.*

sealed trait IncomingMessage:
  val typ:     MessageType
  val from:    RemoteAddress
  val to:      Address
  val payload: Map[Address, MemberState]

sealed trait OutgoingMessage:
  val typ:     MessageType
  val from:    Address
  val to:      RemoteAddress
  val payload: Map[Address, MemberState]

case class TerminatingMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      CurrentAddress,
  payload: Map[Address, MemberState]
) extends IncomingMessage

case class InitiatingMessage(
  typ:     MessageType,
  from:    CurrentAddress,
  to:      RemoteAddress,
  payload: Map[Address, MemberState]
) extends OutgoingMessage

object InitiatingMessage:
  inline def apply(typ: MessageType, from: CurrentAddress, to: RemoteAddress): InitiatingMessage =
    InitiatingMessage(typ, from, to, Map())

case class RedirectMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      RemoteAddress,
  payload: Map[Address, MemberState]
) extends IncomingMessage, OutgoingMessage
