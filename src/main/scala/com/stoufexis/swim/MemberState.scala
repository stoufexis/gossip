package com.stoufexis.swim

import com.stoufexis.swim.util.Codec

enum MemberState:
  case Alive, Failed//, Suspicious

  def isOperational: Boolean = this match
    case Alive => true
    case Failed => false
  
object MemberState:
  given Codec[MemberState] =
    Codec.enumCodec(
      _.ordinal,
      i => Option.when(i >= 0 && i < MemberState.values.length)(MemberState.fromOrdinal(i))
    )