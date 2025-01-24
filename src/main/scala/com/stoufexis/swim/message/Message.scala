package com.stoufexis.swim.message

import zio.Chunk

import com.stoufexis.swim.address.*
import com.stoufexis.swim.members.*

sealed trait IncomingMessage:
  val typ:     MessageType
  val from:    RemoteAddress
  val to:      Address
  val payload: Chunk[(Address, MemberState)]

sealed trait OutgoingMessage:
  val typ:     MessageType
  val from:    Address
  val to:      RemoteAddress
  val payload: Chunk[(Address, MemberState)]

case class TerminatingMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      CurrentAddress,
  payload: Chunk[(Address, MemberState)]
) extends IncomingMessage

case class InitiatingMessage(
  typ:     MessageType,
  from:    CurrentAddress,
  to:      RemoteAddress,
  payload: Chunk[(Address, MemberState)]
) extends OutgoingMessage

object InitiatingMessage:
  inline def apply(typ: MessageType, from: CurrentAddress, to: RemoteAddress): InitiatingMessage =
    InitiatingMessage(typ, from, to, Chunk())

case class RedirectMessage(
  typ:     MessageType,
  from:    RemoteAddress,
  to:      RemoteAddress,
  payload: Chunk[(Address, MemberState)]
) extends IncomingMessage, OutgoingMessage
