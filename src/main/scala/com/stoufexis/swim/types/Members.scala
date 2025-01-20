package com.stoufexis.swim.types

case class Members(current: Address, others: Set[Address]):
  def othersIndexed: IndexedSeq[Address] =
    others.toArray

  def othersIndexedWithout(addr: Address): IndexedSeq[Address] =
    (others - addr).toArray

  infix def -(addr: Address): Members = Members(current, others - addr)

  infix def +(addr: Address): Members =
    if addr == current then this else Members(current, others - addr)

object Members:
  def current(current: Address): Members = Members(current, Set.empty)
