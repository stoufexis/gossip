package com.stoufexis.swim.model

import com.stoufexis.swim.tick.Ticks

case class MemberInfo(
  status:           MemberStatus,
  disseminatedCnt:  Int,
  incarnation:      Int,
  lastStatusUpdate: Ticks
):
  def disseminated: MemberInfo =
    copy(disseminatedCnt = disseminatedCnt + 1)

  def suspected(at: Ticks): MemberInfo =
    copy(status = MemberStatus.Suspicious, lastStatusUpdate = at)

  def failed(at: Ticks): MemberInfo =
    copy(status = MemberStatus.Failed, lastStatusUpdate = at)

  def isSuspectedSince(since: Ticks): Boolean =
    status == MemberStatus.Suspicious && lastStatusUpdate <= since
