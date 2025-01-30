package com.stoufexis.swim.model

import com.stoufexis.swim.comms.Codec

enum MemberStatus derives Codec:
  case Alive, Suspicious, Failed

  def isOperational: Boolean = this match
    case Alive      => true
    case Suspicious => true
    case Failed     => false