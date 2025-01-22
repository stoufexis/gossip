package com.stoufexis.swim.message

import com.stoufexis.swim.address.Address
import com.stoufexis.swim.address.RemoteAddress

enum IncomingMessage:
  val from: RemoteAddress
  val to:   Address

  case Ping(override val from: RemoteAddress, override val to: Address)
  case Ack(override val from: RemoteAddress, override val to: Address)
  case Join(override val from: RemoteAddress, override val to: Address)
  case JoinAck(override val from: RemoteAddress, override val to: Address)