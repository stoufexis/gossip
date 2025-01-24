package com.stoufexis.swim.message

import com.stoufexis.swim.util.Codec

enum MessageType derives Codec:
  case Ping, Ack, Join, JoinAck