package com.stoufexis.swim.members

import zio.Chunk

import com.stoufexis.swim.address.*
import com.stoufexis.swim.message.*
import com.stoufexis.swim.util.*
import com.stoufexis.swim.util.Codec.given_Codec_*:

import scala.annotation.tailrec

/** @return
  *   the bytes and the addresses that were included in the payload after applying the bound.
  */
def encodeBounded(msg: OutgoingMessage, maxBytes: Int): (Map[Address, MemberState], Chunk[Byte]) =
  val fieldsCodec:  Codec[(MessageType, Address, Address)] = summon
  val payloadCodec: Codec[(Address, MemberState)]          = summon

  val fieldsBytes:    Chunk[Byte] = fieldsCodec.encode(msg.typ, msg.from, msg.to)
  val remainingSpace: Int         = maxBytes - fieldsBytes.size

  @tailrec
  def loop(
    remainder: Chunk[(Address, MemberState)],
    included:  Map[Address, MemberState],
    bytes:     Chunk[Byte]
  ): (Map[Address, MemberState], Chunk[Byte]) =
    if remainder.isEmpty then
      (included, bytes)
    else
      val next = remainder.head
      val bs   = payloadCodec.encode(next)
      if bytes.size + bs.size > remainingSpace then
        (included, bytes)
      else
        loop(remainder.tail, included + next, bytes ++ bs)

  loop(msg.payload, Map(), fieldsBytes)

def decode(bytes: Chunk[Byte], currentAddress: CurrentAddress): Either[String, IncomingMessage] =
  val codec: Codec[(MessageType, Address, Address, Chunk[(Address, MemberState)])] = summon

  codec.decode(bytes) match
    case None =>
      Left("Decoding error")

    case Some((remainder, _)) if remainder.nonEmpty =>
      Left("Unexpected remainder")

    case Some((_, (typ, from, to, payload))) =>
      (currentAddress.check(from), currentAddress.check(to)) match
        case (Left(from), Right(to)) => Right(TerminatingMessage(typ, from, to, payload))
        case (Left(from), Left(to))  => Right(RedirectMessage(typ, from, to, payload))
        case (Right(_), _)           => Left("Message had the current address in the `from` field")
