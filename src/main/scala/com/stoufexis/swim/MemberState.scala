package com.stoufexis.swim

enum MemberState:
  case Alive, Failed//, Suspicious

  def isOperational: Boolean = this match
    case Alive => true
    case Failed => false
  