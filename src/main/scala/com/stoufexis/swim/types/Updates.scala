package com.stoufexis.swim.types

import scala.collection.immutable.Queue

opaque type Updates = Queue[Update]

object Updates:
  extension (us: Updates)
    inline def failed(member: Address): Updates = us.enqueue(Update.Failed(member))
    inline def joined(member: Address): Updates = us.enqueue(Update.Joined(member))
