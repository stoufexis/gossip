package com.stoufexis.swim.types

enum Message:
  case Ping(pinger: Address, acker: Address)
  case Ack(pinger: Address, acker: Address)