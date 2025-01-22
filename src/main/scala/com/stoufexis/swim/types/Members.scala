package com.stoufexis.swim.types

// Add MemberState to members
// Updates sent to other nodes are simply the state of nodes in the memberlist
case class Members(current: Address, others: Map[Address, MemberState]):
  def setAlive(addr: Address): Members = ???

  def setFailed(addr: Address): Members = ???

  def isOperational(addr: Address): Boolean = ???

  def getOperational: IndexedSeq[Address] = ???

  def getOperationalWithout(addr: Address): IndexedSeq[Address] = ???

object Members:
  def current(current: Address): Members = ???