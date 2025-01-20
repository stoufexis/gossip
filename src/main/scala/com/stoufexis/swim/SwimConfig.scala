package com.stoufexis.swim

import zio.Duration
import types.Address

/** @param address
  * @param receiveBufferSize
  * @param tickSpeed
  *   Delay added between iterations of the main runloop. Each iteration sends pings to external nodes, checks
  *   if a timeout or ping period has expired, and responds to messages that have been enqueued into the input
  *   buffer.
  * @param timeoutPeriod
  * @param pingPeriod
  * @param failureDetectionSubgroupSize
  * @param disseminationConstant
  */
case class SwimConfig(
  address:                      Address,
  receiveBufferSize:            Int = 1024,
  tickSpeed:                    Duration,
  timeoutPeriodTicks:           Int,
  pingPeriodTicks:              Int,
  failureDetectionSubgroupSize: Int,
  disseminationConstant:        Int
)
