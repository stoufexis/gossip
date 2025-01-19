package com.stoufexis.swim

import java.nio.channels.DatagramChannel


object Node1:
  val channel = DatagramChannel.open()
  channel.close()
  // channel.receive()