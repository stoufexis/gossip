package com.stoufexis.swim

import zio.Duration

import com.stoufexis.swim.model.*
import com.stoufexis.swim.model.Address.*
import com.stoufexis.swim.util.*

case class SwimConfig(
  address:                      CurrentAddress,
  seedNodes:                    NonEmptySet[RemoteAddress],
  receiveBufferSize:            Int,
  tickSpeed:                    Duration,
  directPingPeriodTicks:        Int,
  pingPeriodTicks:              Int,
  joinPeriodTicks:              Int,
  suspectedPeriodTicks:         Int,
  failureDetectionSubgroupSize: Int,
  disseminationConstant:        Int,
  maxTransmissionUnit:          Int
)

object SwimConfig:
  def apply(
    address:                      Address,
    seedNodes:                    NonEmptySet[Address],
    receiveBufferSize:            Int = 1024,
    tickSpeed:                    Duration,
    directPingPeriodTicks:        Int,
    pingPeriodTicks:              Int,
    joinPeriodTicks:              Int,
    suspectedPeriodTicks:         Int,
    failureDetectionSubgroupSize: Int,
    disseminationConstant:        Int,
    maxTransmissionUnit:          Int
  ): SwimConfig =
    // convert addresses to remote, current, checking validity
    ???
