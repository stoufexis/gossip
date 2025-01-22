package com.stoufexis.swim


import scala.collection.immutable.Queue
import com.stoufexis.swim.address.*

// Add MemberState to members
// Updates sent to other nodes are simply the state of nodes in the memberlist
case class Members(others: Map[RemoteAddress, MemberState], updates: Queue[(RemoteAddress, MemberState)]):
  def setAlive(addr: RemoteAddress): Members =
    ???

  def setFailed(addr: RemoteAddress): Members = ???

  def isOperational(addr: RemoteAddress): Boolean = ???

  def getOperational: Vector[RemoteAddress] = ???

  def getOperationalWithout(addr: RemoteAddress): Vector[RemoteAddress] = ???

object Members:
  def empty: Members = ???