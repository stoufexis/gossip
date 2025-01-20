package com.stoufexis.swim.types

enum Update:
  case Failed(member: Address)
  case Joined(member: Address)
