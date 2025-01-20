package com.stoufexis.swim

import zio.*

object Main extends ZIOAppDefault:
  override def run: ZIO[Any & (ZIOAppArgs & Scope), Any, Any] =
    ???