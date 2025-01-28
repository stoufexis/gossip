package com.stoufexis.swim

import zio.Chunk

import com.stoufexis.swim.address.*
import com.stoufexis.swim.address.Address.*
import com.stoufexis.swim.members.*
import com.stoufexis.swim.message.*
import com.stoufexis.swim.tick.*


// Updates sent to other nodes are simply the state of nodes in the memberlist
/** @param map
  * @param disseminationLimit
  *   referenced as λ in the
  *   [[SWIM paper https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf]]
  */
case class State(
  waitingOnAck:       Option[RemoteAddress],
  tick:               Ticks,
  joiningVia:         Option[RemoteAddress],
  members:            Map[RemoteAddress, (MemberState, Int)],
  disseminationLimit: Int
):
  private def setMembers(members2: Map[RemoteAddress, (MemberState, Int)]): State =
    copy(members = members2)

  def setJoiningVia(value: Option[RemoteAddress]): State =
    copy(joiningVia = value)

  def setWaitingOnAck(value: Option[RemoteAddress]): State =
    copy(waitingOnAck = value)

  def set(addr: RemoteAddress, ms: MemberState): State =
    setMembers(members.updated(addr, (ms, 0)))

  def setAlive(addr: RemoteAddress): State =
    set(addr, MemberState.Alive)

  def setFailed(addr: RemoteAddress): State =
    set(addr, MemberState.Failed)

  def isOperational(addr: RemoteAddress): Boolean =
    members.get(addr).exists(_._1.isOperational)

  def getOperational: Set[RemoteAddress] =
    members.filter(_._2._1.isOperational).keySet

  def getOperationalWithout(addr: RemoteAddress): Set[RemoteAddress] =
    members.removed(addr).filter(_._2._1.isOperational).keySet

  def resetTicks: State = 
    copy(tick = Ticks.zero)

  def setTicks(ticks: Ticks): State = 
    copy(tick = ticks)

  def addTicks(ticks: Ticks): State = 
    copy(tick = tick + ticks)

  /**
    * Appends the given updates and returns a series of updates that should be disseminated
    * to the node from which the append updates originated.
    * 
    * The returned updates are for nodes that were not included in the append updates.
    * Sorted by asceding dissemination count.
    */
  def appendAndGet(append: Chunk[Payload]): (Chunk[Payload], State) =
    ???

  /** @return
    *   The latest member states per remote address. If an element has been disseminated λ*log(n) times, it is
    *   skipped. The list is ordered by ascending dissemination count, i.e. the elements that have been
    *   disseminated fewer times are first.
    */
  def updates: Chunk[Payload] =
    val cutoff = (disseminationLimit * Math.log10(members.size)).toInt

    // The memberlist should be relatively small (a few hundreads of elements at most usually)
    // so we will accept this slightly unoptimized series of operations for now.
    Chunk
      .from(members)
      .sortBy { case (_, (_, dc)) => dc }
      .takeWhile { case (_, (_, dc)) => dc <= cutoff }
      .map { case (add, (st, _)) => Payload(add, st) }

  def append(chunk: Chunk[Payload]): State = setMembers:
    chunk.foldLeft(members): (acc, payload) =>
      acc.updatedWith(payload.add):
        // dont reset the counter to 0 if we already knew about the member's state
        case s @ Some((state, _)) if state == payload.ms => s
        case _                                           => Some(payload.ms, 0)

  /** Increments the dissemination count for the latest updates of the given addresses
    */
  def disseminated(add: Set[RemoteAddress]): State = setMembers:
    add.foldLeft(members): (acc, address) =>
      acc.updatedWith(address)(_.map((ms, dc) => (ms, dc + 1)))