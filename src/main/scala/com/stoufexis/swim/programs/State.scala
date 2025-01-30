package com.stoufexis.swim.programs

import zio.Chunk

import com.stoufexis.swim.model.*
import com.stoufexis.swim.model.Address.*
import com.stoufexis.swim.programs.State.overrides
import com.stoufexis.swim.tick.*

/** @param waitingOnAck
  * @param joiningVia
  * @param members
  * @param currentInfo
  *   An update about the current node that should be disseminated
  */
case class State(
  waitingOnAck: Waiting,
  joiningVia:   Waiting,
  members:      Map[RemoteAddress, MemberInfo],
  currentInfo:  MemberInfo
):
  private def cutoff(disseminationConstant: Int): Int =
    (disseminationConstant * Math.log10(members.size)).toInt

  private def updateMember(member: RemoteAddress)(f: Option[MemberInfo] => Option[MemberInfo]): State =
    copy(members = members.updatedWith(member)(f))

  def setJoining(via: RemoteAddress, at: Ticks): State =
    copy(joiningVia = Waiting.CurrentlyWaiting(via, at))

  def clearJoining: State =
    joiningVia match
      case Waiting.NeverWaited                => this
      case Waiting.LastWaited(_)              => this
      case Waiting.CurrentlyWaiting(_, since) => copy(joiningVia = Waiting.LastWaited(since))

  def setWaitingOnAck(waitingOn: RemoteAddress, at: Ticks): State =
    copy(waitingOnAck = Waiting.CurrentlyWaiting(waitingOn, at))

  def clearWaitingOnAck: State =
    waitingOnAck match
      case Waiting.NeverWaited                => this
      case Waiting.LastWaited(_)              => this
      case Waiting.CurrentlyWaiting(_, since) => copy(waitingOnAck = Waiting.LastWaited(since))

  def setSuspicious(addr: RemoteAddress, now: Ticks): State =
    val newStatus = MemberStatus.Suspicious
    members.get(addr) match
      case Some(MemberInfo(_, _, incarnation, _)) =>
        append(Update(addr, newStatus, incarnation), now)

      // Dont create an entry for a non-existent node
      // this case should be impossible to reach, as we do not delete nodes from the member list
      case None => this

  def isOperational(addr: RemoteAddress): Boolean =
    members.get(addr).exists(_._1.isOperational)

  def getOperational: Set[RemoteAddress] =
    members.filter(_._2._1.isOperational).keySet

  def getOperationalWithout(addr: RemoteAddress): Set[RemoteAddress] =
    members.removed(addr).filter(_._2._1.isOperational).keySet

  def updateOverdueSuspicious(suspectedSince: Ticks, now: Ticks): (Set[RemoteAddress], State) =
    members.foldLeft((Set.empty[RemoteAddress], this)):
      case (acc @ (addresses, state), (address, info)) =>
        if info.isSuspectedSince(suspectedSince) then
          (addresses + address, state.updateMember(address)(_.map(_.failed(now))))
        else
          acc

  /** Appends the given updates and returns a series of updates that should be disseminated to the node from
    * which the append updates originated.
    *
    * The returned updates are for nodes that were not included in the append updates. Sorted by asceding
    * dissemination count.
    */
  def appendAndGet(append: Chunk[Update]): (Chunk[Update], State) =
    ???

  /** @return
    *   The latest member states per remote address. If an element has been disseminated λ*log(n) times, it is
    *   skipped. The list is ordered by ascending dissemination count, i.e. the elements that have been
    *   disseminated fewer times are first.
    */
  def updates(currentAddress: CurrentAddress, disseminationConstant: Int): Chunk[Update] =
    // The memberlist should be relatively small (a few hundreads of elements at most usually)
    // so we will accept this unoptimized series of operations for now.
    Chunk
      .from(members)
      .appended(currentAddress, currentInfo)
      .sortBy { (_, info) => info.disseminatedCnt }
      .takeWhile { (_, info) => info.disseminatedCnt <= cutoff(disseminationConstant) }
      .map { (add, info) => Update(add, info.status, info.incarnation) }

  def append(update: Update, now: Ticks): State =
    update.address match
      case address: RemoteAddress =>
        updateMember(address):
          case current @ Some(MemberInfo(status, _, inc, _)) =>
            if overrides(update.status, update.incarnation, status, inc) then
              Some(MemberInfo(update.status, 0, update.incarnation, now))
            else
              current

          case _ =>
            Some(MemberInfo(update.status, 0, update.incarnation, now))

      case _: CurrentAddress =>
        update.status match
          case MemberStatus.Suspicious if currentInfo.incarnation == update.incarnation =>
            copy(currentInfo = MemberInfo(MemberStatus.Alive, 0, currentInfo.incarnation + 1, now))

          case _ => this

  def append(chunk: Chunk[Update], now: Ticks): State =
    chunk.foldLeft(this)((acc, update) => acc.append(update, now))

  def disseminated(add: Address): State =
    add match
      case address: RemoteAddress =>
        updateMember(address)(_.map(_.disseminated))

      case _: CurrentAddress =>
        copy(currentInfo = currentInfo.disseminated)

  /** Increments the dissemination count for the latest updates of the given addresses
    */
  def disseminated(add: Set[Address]): State =
    add.foldLeft(this)((acc, address) => acc.disseminated(address))

object State:
  /** Checks if the first status/incarnation pair overrides the second
    */
  def overrides(status1: MemberStatus, incarnation1: Int, status2: MemberStatus, incarnation2: Int): Boolean =
    status1 match
      case MemberStatus.Alive =>
        status2 match
          case MemberStatus.Alive      => incarnation1 > incarnation2
          case MemberStatus.Suspicious => incarnation1 > incarnation2
          case _                       => false

      case MemberStatus.Suspicious =>
        status2 match
          case MemberStatus.Alive      => incarnation1 >= incarnation2
          case MemberStatus.Suspicious => incarnation1 > incarnation2
          case _                       => false

      case MemberStatus.Failed => true
