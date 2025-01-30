package com.stoufexis.swim.model

import com.stoufexis.swim.comms.Codec

enum MessageType derives Codec:
  case Ping, Ack, Join, JoinAck