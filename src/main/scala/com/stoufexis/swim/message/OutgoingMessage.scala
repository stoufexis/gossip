package com.stoufexis.swim.message

import com.stoufexis.swim.address.*

enum OutgoingMessage:
  val from: Address
  val to:   RemoteAddress

  case Ping(override val from: Address, override val to: RemoteAddress)
  case Ack(override val from: Address, override val to: RemoteAddress)
  case Join(override val from: Address, override val to: RemoteAddress)
  case JoinAck(override val from: Address, override val to: RemoteAddress)