package com.stoufexis.swim.model

import com.stoufexis.swim.comms.Codec

enum MemberState derives Codec:
  case Alive, Failed//, Suspicious

  def isOperational: Boolean = this match
    case Alive => true
    case Failed => false