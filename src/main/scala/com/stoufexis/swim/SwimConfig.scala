package com.stoufexis.swim

import zio.Duration

import com.stoufexis.swim.address.*

case class SwimConfig(
  address:                      CurrentAddress,
  seedNodes:                    Set[RemoteAddress],
  receiveBufferSize:            Int,
  tickSpeed:                    Duration,
  directPingPeriodTicks:        Int,
  pingPeriodTicks:              Int,
  joinPeriodTicks:              Int,
  failureDetectionSubgroupSize: Int,
  disseminationConstant:        Int
)

object SwimConfig:
  def apply(
    address:                      Address,
    seedNodes:                    Set[Address],
    receiveBufferSize:            Int = 1024,
    tickSpeed:                    Duration,
    directPingPeriodTicks:        Int,
    pingPeriodTicks:              Int,
    joinPeriodTicks:              Int,
    failureDetectionSubgroupSize: Int,
    disseminationConstant:        Int
  ): Either[String, SwimConfig] =
    // convert addresses to remote, current, checking validity
    ???
