package com.stoufexis.swim.comms

import zio.*

import com.stoufexis.swim.address.*
import com.stoufexis.swim.members.*
import com.stoufexis.swim.message.*

import scala.annotation.tailrec

object Serde:
  // types only used for serialization/deserializatiob
  case class Meta(mt: MessageType, from: Address, to: Address) derives Codec
  case class Message(mt: MessageType, from: Address, to: Address, pa: Chunk[Payload]) derives Codec

  /** @return
    *   the bytes and the addresses that were included in the payload after applying the bound.
    */
  def encodeBounded(msg: OutgoingMessage, maxBytes: Int): (Map[Address, MemberState], Chunk[Byte]) =
    val fieldsBytes:    Chunk[Byte] = Codec.encode(Meta(msg.typ, msg.from, msg.to))
    val remainingSpace: Int         = maxBytes - fieldsBytes.size

    @tailrec
    def loop(
      remainder: Chunk[Payload],
      included:  Map[Address, MemberState],
      bytes:     Chunk[Byte]
    ): (Map[Address, MemberState], Chunk[Byte]) =
      if remainder.isEmpty then
        (included, bytes)
      else
        val next = remainder.head
        val bs   = Codec.encode(next)
        if bytes.size + bs.size > remainingSpace then
          (included, bytes)
        else
          loop(remainder.tail, included + (next.add -> next.ms), bytes ++ bs)

    loop(msg.payload, Map(), fieldsBytes)

  def decode(bytes: Chunk[Byte], currentAddress: CurrentAddress): Either[String, IncomingMessage] =
    Codec.decode[Message](bytes) match
      case None =>
        Left("Decoding error")

      case Some((remainder, _)) if remainder.nonEmpty =>
        Left("Unexpected remainder")

      case Some((_, Message(typ, from, to, payload))) =>
        (currentAddress.check(from), currentAddress.check(to)) match
          case (Left(from), Right(to)) => Right(TerminatingMessage(typ, from, to, payload))
          case (Left(from), Left(to))  => Right(RedirectMessage(typ, from, to, payload))
          case (Right(_), _)           => Left("Message had the current address in the `from` field")
