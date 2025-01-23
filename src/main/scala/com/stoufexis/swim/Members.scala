package com.stoufexis.swim

import com.stoufexis.swim.address.*

// Updates sent to other nodes are simply the state of nodes in the memberlist
case class Members(map: Map[RemoteAddress, (MemberState, Int)]):
  def set(addr: RemoteAddress, ms: MemberState): Members =
    Members(map.updated(addr, (ms, 0)))

  def setAlive(addr: RemoteAddress): Members =
    set(addr, MemberState.Alive)

  def setFailed(addr: RemoteAddress): Members =
    set(addr, MemberState.Failed)

  def isOperational(addr: RemoteAddress): Boolean =
    map.get(addr).exists(_._1.isOperational)

  def getOperational: Set[RemoteAddress] =
    map.filter(_._2._1.isOperational).keySet

  def getOperationalWithout(addr: RemoteAddress): Set[RemoteAddress] =
    map.removed(addr).filter(_._2._1.isOperational).keySet

  /** @param disseminationLimitConstant
    *   referenced as λ in the
    *   [[SWIM paper https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf]]
    * @return
    *   The latest member states per remote address. If an element has been disseminated λ*log(n) times, it is
    *   skipped. The list is ordered by ascending dissemination count, i.e. the elements that
    *   have been disseminated fewer times are first.
    */
  def updates(disseminationLimitConstant: Int): List[(RemoteAddress, MemberState)] =
    val cutoff = (disseminationLimitConstant * Math.log10(map.size)).toInt
  
    // The memberlist should be relatively small (a few hundreads of elements at most usually)
    // so we will accept this slightly unoptimized series of operations for now.
    List
      .from(map)
      .sortBy { case (_, (_, dc)) => dc }
      .takeWhile { case (_, (_, dc))  => dc <= cutoff }
      .map { case (add, (st, _)) => (add, st) }

  /** Increments the dissemination count for the latest updates of the given addresses
    */
  def disseminated(add: Set[RemoteAddress]): Members =
    Members(map.map { case (add, (ms, dc)) => (add, (ms, dc + 1)) })

object Members:
  def empty: Members = Members(Map.empty)
