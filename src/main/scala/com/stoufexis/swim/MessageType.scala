package com.stoufexis.swim

import com.stoufexis.swim.util.Codec

enum MessageType derives Codec:
  case Ping, Ack, Join, JoinAck