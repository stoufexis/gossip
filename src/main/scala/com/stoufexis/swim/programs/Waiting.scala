package com.stoufexis.swim.programs

import com.stoufexis.swim.model.Address.RemoteAddress
import com.stoufexis.swim.tick.Ticks

enum Waiting:
  case NeverWaited
  case LastWaited(at: Ticks)
  case CurrentlyWaiting(address: RemoteAddress, since: Ticks)

  def currentlyWaitingFor(address: RemoteAddress): Boolean =
    this match
      case CurrentlyWaiting(address1, _) => address == address1
      case _                             => false
