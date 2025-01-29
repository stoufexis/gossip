package com.stoufexis.swim.model

import com.stoufexis.swim.comms.Codec

enum MessageType derives Codec:
  case Ping, Ack, Join, JoinAck

  def print: String = this match
    case Ping    => "PING"
    case Ack     => "ACK"
    case Join    => "JOIN"
    case JoinAck => "JOIN_ACK"
