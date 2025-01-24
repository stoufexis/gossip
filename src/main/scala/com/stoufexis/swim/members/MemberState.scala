package com.stoufexis.swim.members

import com.stoufexis.swim.util.Codec

enum MemberState derives Codec:
  case Alive, Failed//, Suspicious

  def isOperational: Boolean = this match
    case Alive => true
    case Failed => false