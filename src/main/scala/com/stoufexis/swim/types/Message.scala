package com.stoufexis.swim.types

enum Message:
  /**
    * @param pinger The node who generated the Ping
    * @param acker The node which the Ping should reach
    */
  case Ping(pinger: Address, acker: Address)

  /**
    * @param pinger The node which the Ack should reach
    * @param acker The node who generated the Ack
    */
  case Ack(pinger: Address, acker: Address)