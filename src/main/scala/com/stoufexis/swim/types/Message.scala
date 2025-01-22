package com.stoufexis.swim.types

enum Message:
  val from: Address
  val to: Address

  case Ping(override val from: Address, override val to: Address)
  case Ack(override val from: Address, override val to: Address)

  // To be added. Results in the node being added as alive to the member list of the target node
  // case Join(override val from: Address, override val to: Address)