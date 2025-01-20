package com.stoufexis.swim

enum Update:
  case Failed(member: Address)
  case Joined(member: Address)
