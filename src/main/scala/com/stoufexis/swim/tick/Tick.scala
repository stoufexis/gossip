package com.stoufexis.swim.tick

import zio.*
import zio.Schedule.*

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

opaque type Ticks = Long

object Ticks:
  def zero: Ticks = 0

  extension (t: Ticks)
    inline infix def +(t1:  Ticks): Ticks   = t + t1
    inline infix def -(t1:  Ticks): Ticks   = t - t1
    inline infix def -(i:   Int):   Ticks   = t - i
    inline infix def >(t1:  Ticks): Boolean = t > t1
    inline infix def <(t1:  Ticks): Boolean = t < t1
    inline infix def <=(t1: Ticks): Boolean = t <= t1
    inline infix def >(i:   Int):   Boolean = t > i
    inline infix def <(i:   Int):   Boolean = t < i
    inline infix def ==(t1: Ticks): Boolean = t == t1
    inline infix def prev: Ticks = t - 1

  def schedule(tickEvery: Long): Schedule[Any, Any, Ticks] = new:
    type State = Option[OffsetDateTime]

    def initial: State = None

    def step(now: OffsetDateTime, in: Any, previous: State)(using Trace): UIO[(State, Ticks, Decision)] =
      previous match
        case None =>
          val decision: Decision =
            Decision.Continue(Interval.after(now.plus(Duration.fromMillis(tickEvery))))

          ZIO.succeed(Some(now), 0, decision)

        case Some(before) =>
          val passedTicks: Long =
            Duration.fromInterval(before, now).toMillis / tickEvery

          val alignedNow: OffsetDateTime =
            now.minus(now.toInstant().toEpochMilli() % tickEvery, ChronoUnit.MILLIS)

          val decision: Decision =
            Decision.Continue(Interval.after(alignedNow.plus(Duration.fromMillis(tickEvery))))

          ZIO.succeed(Some(before), passedTicks, decision)
