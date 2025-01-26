package com.stoufexis.swim

import zio.*
import zio.stream.*

import com.stoufexis.swim.address.*
import com.stoufexis.swim.address.Address.*
import com.stoufexis.swim.comms.Comms
import com.stoufexis.swim.members.*
import com.stoufexis.swim.message.*
import com.stoufexis.swim.tick.*

object Swim:
  def run: RIO[Comms & SwimConfig, Nothing] =
    ZIO.service[Comms]
      .zip(ZIO.service[SwimConfig])
      .flatMap(runWithEnv(_, _))

  // TODO reduce overall duplication and move to seperate files maybe
  def runWithEnv(comms: Comms, cfg: SwimConfig): Task[Nothing] =
    import MessageType.*

    case class State(
      waitingOnAck: Option[RemoteAddress],
      members:      Members,
      tick:         Ticks,
      joiningVia:   Option[RemoteAddress]
    )

    def handleMessages(st: State): Task[State] =
      /** Note that when receiving a message that requires redirection a warning is logged, as it could
        * indicate partial system or networking failures.
        */

      def loop(remainder: Chunk[IncomingMessage], acc: State): Task[State] =
        def tail = remainder.tail

        remainder.headOption match
          case None =>
            ZIO.succeed(acc)

          case Some(msg) if !st.members.isOperational(msg.from) =>
            ZIO.logWarning(s"Dropping message from ${msg.from}, as it is not an operational member")
              *> loop(tail, acc)

          case Some(msg) =>
            acc.joiningVia match
              // Behavior when we are in the join process
              case Some(joiningVia) =>
                // The only message we respond to is an expected joinAck directed at us
                msg match
                  case TerminatingMessage(JoinAck, from, _, pl) if joiningVia == from =>
                    ZIO.logInfo(s"Node $from confirmed our join")
                      *> loop(tail, acc.copy(joiningVia = None, members = acc.members.append(pl)))

                  case _ =>
                    ZIO.logInfo(s"Dropping ${msg.typ} message from ${msg.from} as we are still joining")
                      *> loop(tail, acc)

              // Behavior when we are not in the join process
              case None =>
                msg match
                  case RedirectMessage(_, _, to, _) if !st.members.isOperational(to) =>
                    ZIO.logWarning(s"Dropping message aimed at $to, as it is not an operational member")
                      *> loop(tail, acc)

                  case msg @ RedirectMessage(_, _, to, pl) =>
                    ZIO.logWarning(s"Redirecting message to $to")
                      *> comms.sendMessage(to, msg)
                      *> loop(tail, acc.copy(members = acc.members.append(pl)))

                  case TerminatingMessage(Ping, from, _, pl) =>
                    ZIO.logDebug(s"Acking ping from $from")
                      *> comms.sendMessage(from, InitiatingMessage(Ack, from = cfg.address, to = from))
                      *> loop(tail, acc.copy(members = acc.members.append(pl)))

                  case TerminatingMessage(Ack, from, _, pl) if st.waitingOnAck.exists(_ == from) =>
                    ZIO.logDebug(s"Received valid ack from $from")
                      *> loop(tail, acc.copy(waitingOnAck = None, members = acc.members.append(pl)))

                  // This message is likely old, so we dont accept its payload to guard against stale information
                  case TerminatingMessage(Ack, from, _, _) =>
                    ZIO.logWarning(s"Dropping unexpected ack from $from")
                      *> loop(tail, acc)

                  case TerminatingMessage(Join, from, _, pl) =>
                    val warn: UIO[Unit] =
                      ZIO.logWarning(s"Received Join with non-empty payload from $from")

                    ZIO.logInfo(s"Node $from is joining the cluster")
                      *> ZIO.when(pl.nonEmpty)(warn)
                      *> comms.sendMessage(from, InitiatingMessage(JoinAck, from = cfg.address, to = from))
                      *> loop(tail, acc.copy(members = acc.members.setAlive(from)))

                  // This message is likely old, so we dont accept its payload to guard against stale information
                  case TerminatingMessage(JoinAck, from, _, _) =>
                    ZIO.logWarning(s"Received unexpected join ack from $from")
                      *> loop(tail, acc)
      end loop

      comms.receiveMessage.flatMap(loop(_, st))

    end handleMessages

    def sendMessages(st: State, ticks: Ticks): Task[State] = st.joiningVia match
      // We are currently in the join process and the join timeout has been exceeded. Pick a different seed node
      case Some(joiningVia) if st.tick + ticks > cfg.joinPeriodTicks =>
        comms.sendJoin(Some(joiningVia)).map: newJoiningVia =>
          st.copy(tick = Ticks.zero, joiningVia = Some(newJoiningVia))

      // We are currently in the join process and the join timeout has not been exceeded
      case Some(joiningVia) =>
        ZIO.succeed(st.copy(tick = st.tick + ticks, joiningVia = Some(joiningVia)))

      // We are not in the joining process anymore, we begin pinging after a ping period from our last join message
      case None =>
        st.waitingOnAck match
          // We are not waiting for any ack and a ping period passed. Ping another member.
          case None if st.tick + ticks > cfg.pingPeriodTicks =>
            comms.pingRandomMember(st.members).map: waitingOnAck =>
              st.copy(waitingOnAck = waitingOnAck, tick = Ticks.zero)

          // Ping period passed and we have not received an ack for the pinged member. They have failed.
          case Some(waitingOnAck) if st.tick + ticks > cfg.pingPeriodTicks =>
            for
              _               <- ZIO.logWarning(s"Ping period expired. Declaring $waitingOnAck as failed")
              newWaitingOnAck <- comms.pingRandomMember(st.members)
            yield st.copy(
              waitingOnAck = newWaitingOnAck,
              members      = st.members.setFailed(waitingOnAck),
              tick         = Ticks.zero
            )

          // Direct ping period passed and we have not received an ack for the pinged member. Ping indirectly.
          case Some(waitingOnAck) if st.tick + ticks > cfg.directPingPeriodTicks =>
            for
              _ <- ZIO.logWarning(s"Direct ping period expired for $waitingOnAck")
              _ <- comms.pingIndirectly(waitingOnAck, st.members)
            yield st.copy(tick = st.tick + ticks)

          // No period has expired yet, keep waiting for the ack or not waiting for any ack
          case _ =>
            ZIO.succeed(st.copy(tick = st.tick + ticks))

    end sendMessages

    for
      joiningVia: RemoteAddress <-
        comms.sendJoin()

      initState = State(
        waitingOnAck = None,
        members      = members.Members.empty,
        tick         = Ticks.zero,
        joiningVia   = Some(joiningVia)
      )

      _ <-
        ZStream
          .fromSchedule(Ticks.schedule(cfg.tickSpeed.toMillis))
          .runFoldZIO(initState): (st, ts) =>
            handleMessages(st).flatMap(sendMessages(_, ts))

      // We want to signal on the type level that this program will never terminate
      n: Nothing <- ZIO.never
    yield n

  end runWithEnv
