package com.stoufexis.swim.types

enum Message:
  val from: Address
  val to: Address

  case Ping(override val from: Address, override val to: Address)
  case Ack(override val from: Address, override val to: Address)