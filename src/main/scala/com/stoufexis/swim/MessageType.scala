package com.stoufexis.swim

import com.stoufexis.swim.util.Codec

enum MessageType:
  case Ping, Ack, Join, JoinAck

object MessageType:
  given Codec[MessageType] =
    Codec.enumCodec(
      _.ordinal,
      i => Option.when(i >= 0 && i < MessageType.values.length)(MessageType.fromOrdinal(i))
    )