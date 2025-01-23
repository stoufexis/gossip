package com.stoufexis.swim

import com.stoufexis.swim.address.*
import com.stoufexis.swim.util.Codec

sealed trait Message derives Codec:
  val typ:     MessageType
  val from:    Address
  val to:      Address
  val payload: Map[Address, MemberState]

object Message:
  inline def apply(typ: MessageType, from: CurrentAddress, to: RemoteAddress): InitiatingMessage =
    InitiatingMessage(typ, from, to, Map())

sealed trait IncomingMessage extends Message derives Codec:
  val typ:     MessageType
  val from:    RemoteAddress
  val to:      Address
  val payload: Map[Address, MemberState]

sealed trait OutgoingMessage extends Message derives Codec:
  val typ:     MessageType
  val from:    Address
  val to:      RemoteAddress
  val payload: Map[Address, MemberState]

case class TerminatingMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      CurrentAddress,
  payload: Map[Address, MemberState]
) extends IncomingMessage derives Codec

case class InitiatingMessage(
  typ:     MessageType,
  from:    CurrentAddress,
  to:      RemoteAddress,
  payload: Map[Address, MemberState]
) extends OutgoingMessage derives Codec

case class RedirectMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      RemoteAddress,
  payload: Map[Address, MemberState]
) extends IncomingMessage, OutgoingMessage derives Codec
