package com.stoufexis.swim.programs

import com.stoufexis.swim.model.Address.RemoteAddress
import com.stoufexis.swim.tick.Ticks

enum Process:
  case Uninitialized
  case Completed(lastStartedAt: Ticks)
  case InProgress(address: RemoteAddress, since: Ticks)

  def currentlyWaitingFor(address: RemoteAddress): Boolean =
    this match
      case InProgress(address1, _) => address == address1
      case _                       => false
