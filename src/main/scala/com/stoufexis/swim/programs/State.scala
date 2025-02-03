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
  waitingOnAck: Process,
  joiningVia:   Process,
  members:      Map[RemoteAddress, MemberInfo],
  currentInfo:  MemberInfo
):
  private def updateMember(member: RemoteAddress)(f: Option[MemberInfo] => Option[MemberInfo]): State =
    copy(members = members.updatedWith(member)(f))

  private def modifyMember[A](member: RemoteAddress)(f: Option[MemberInfo] => (A, Option[MemberInfo]))
    : (A, State) =
    val (out, replacement) = f(members.get(member))
    out -> updateMember(member)(_ => replacement)

  def setJoining(via: RemoteAddress, at: Ticks): State =
    copy(joiningVia = Process.InProgress(via, at))

  def clearJoining: State =
    joiningVia match
      case Process.Uninitialized        => this
      case Process.Completed(_)         => this
      case Process.InProgress(_, since) => copy(joiningVia = Process.Completed(since))

  def setWaitingOnAck(waitingOn: RemoteAddress, at: Ticks): State =
    copy(waitingOnAck = Process.InProgress(waitingOn, at))

  def clearWaitingOnAck: State =
    waitingOnAck match
      case Process.Uninitialized        => this
      case Process.Completed(_)         => this
      case Process.InProgress(_, since) => copy(waitingOnAck = Process.Completed(since))

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

  private def appendOne(update: Update, now: Ticks): (Boolean, State) =
    update.address match
      case address: RemoteAddress =>
        modifyMember(address):
          case current @ Some(MemberInfo(status, _, inc, _)) =>
            if overrides(update.status, update.incarnation, status, inc) then
              true -> Some(MemberInfo(update.status, 0, update.incarnation, now))
            else
              false -> current

          case _ =>
            true -> Some(MemberInfo(update.status, 0, update.incarnation, now))

      case _: CurrentAddress =>
        update.status match
          case MemberStatus.Suspicious if currentInfo.incarnation == update.incarnation =>
            true -> copy(currentInfo = MemberInfo(MemberStatus.Alive, 0, currentInfo.incarnation + 1, now))

          case _ => false -> this

  private def appendMany(updates: Chunk[Update], now: Ticks): (Set[Address], State) =
    updates.foldLeft((Set.empty[Address], this)):
      case ((updated, state), update) =>
        val (wasUpdated, newState) = state.appendOne(update, now)
        if wasUpdated
        then (updated + update.address, newState)
        else (updated, newState)

  private def updatesExcluding(
    exclude:               Set[Address],
    currentAddress:        CurrentAddress,
    disseminationConstant: Int
  ): Chunk[Update] =
    val cutoff = State.cutoff(disseminationConstant, members.size + 1)
    // The memberlist should be relatively small (a few hundreads of elements at most usually)
    // so we will accept this unoptimized series of operations for now.
    Chunk
      .from(members)
      .appended(currentAddress, currentInfo)
      .filterNot { (add, _) => exclude.contains(add) }
      .sortBy { (_, info) => info.disseminatedCnt }
      .takeWhile { (_, info) => info.disseminatedCnt <= cutoff }
      .map { (add, info) => Update(add, info.status, info.incarnation) }

  /** Appends the given updates and returns a series of updates that should be disseminated to the node from
    * which the append updates originated.
    *
    * The returned updates are for nodes that were not included in the append updates. Sorted by asceding
    * dissemination count.
    */
  def appendAndGet(
    currentAddress:        CurrentAddress,
    updates:               Chunk[Update],
    now:                   Ticks,
    disseminationConstant: Int
  ): (Chunk[Update], State) =
    val (updated, newState) = appendMany(updates, now)
    (newState.updatesExcluding(updated - currentAddress, currentAddress, disseminationConstant), newState)

  /** @return
    *   The latest member states per remote address. If an element has been disseminated λ*log(n) times, it is
    *   skipped. The list is ordered by ascending dissemination count, i.e. the elements that have been
    *   disseminated fewer times are first.
    */
  def updates(currentAddress: CurrentAddress, disseminationConstant: Int): Chunk[Update] =
    updatesExcluding(Set.empty, currentAddress, disseminationConstant)

  def append(update: Update, now: Ticks): State =
    appendOne(update, now)._2

  def append(chunk: Chunk[Update], now: Ticks): State =
    appendMany(chunk, now)._2

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

  def diff(that: State): State.Diff =
    val woa: Option[(Process, Process)] =
      Option.when(this.waitingOnAck == that.waitingOnAck)((this.waitingOnAck, that.waitingOnAck))

    val jv: Option[(Process, Process)] =
      Option.when(this.joiningVia == that.joiningVia)((this.joiningVia, that.joiningVia))

    val cm: Set[(RemoteAddress, (Option[MemberInfo], Option[MemberInfo]))] =
      (this.members.keySet ++ that.members.keySet).collect:
        case addr if this.members.get(addr) != that.members.get(addr) =>
          addr -> (this.members.get(addr), that.members.get(addr))

    val ci: Option[(MemberInfo, MemberInfo)] =
      Option.when(this.currentInfo == that.currentInfo)((this.currentInfo, that.currentInfo))

    State.Diff(
      waitingOnAck   = woa,
      joiningVia     = jv,
      changedMembers = Map.from(cm),
      currentInfo    = ci
    )

object State:
  case class Diff(
    waitingOnAck:   Option[(Process, Process)],
    joiningVia:     Option[(Process, Process)],
    changedMembers: Map[RemoteAddress, (Option[MemberInfo], Option[MemberInfo])],
    currentInfo:    Option[(MemberInfo, MemberInfo)]
  ):
    def print: String =
      waitingOnAck.fold("")((w1, w2) => s"waiting_on_ack: $w1 -> $w2")
        ++ joiningVia.fold("")((j1, j2) => s", join_via: $j1 -> $j2")
        ++ changedMembers.foldLeft("") { case (str, (addr, (before, after))) =>
          str ++ s", member_changed($addr): $before -> $after"
        }
        ++ currentInfo.fold("")((w1, w2) => s", current_info: $w1 -> $w2")

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

  def cutoff(disseminationConstant: Int, groupSize: Int): Int =
    (disseminationConstant * Math.log10(groupSize)).toInt
