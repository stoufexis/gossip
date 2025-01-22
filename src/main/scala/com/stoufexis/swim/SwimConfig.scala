package com.stoufexis.swim

import types.Address
import zio.Duration

case class SwimConfig(
  address:                      Address,
  seedNodes:                    Set[Address],
  receiveBufferSize:            Int = 1024,
  tickSpeed:                    Duration,
  directPingPeriodTicks:        Int,
  pingPeriodTicks:              Int,
  joinPeriodTicks:              Int,
  failureDetectionSubgroupSize: Int,
  disseminationConstant:        Int
)
