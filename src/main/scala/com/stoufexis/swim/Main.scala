package com.stoufexis.swim

import zio.*
import zio.stream.ZStream
import com.stoufexis.swim.Swim.TickerSchedule

object Main extends ZIOAppDefault:
  override def run: ZIO[Any & (ZIOAppArgs & Scope), Any, Any] =
    ZStream
      .fromSchedule(TickerSchedule(100))
      .mapZIO(i => ZIO.sleep(Duration.fromMillis(60)) as i)
      .mapZIO(i => ZIO.logInfo(s"Hello $i"))
      .runDrain
