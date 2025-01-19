package com.stoufexis.swim

import zio.Duration

case class SwimConfig(
  address:                      Address,
  receiveBufferSize:            Int = 1024,
  pingPeriod:                   Duration,
  timeoutPeriod:                Duration,
  tickSpeed:                    Duration,
  failureDetectionSubgroupSize: Int,
  disseminationConstant:        Int
)
