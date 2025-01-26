package com.stoufexis.swim.comms

import zio.*

import com.stoufexis.swim.address.*
import com.stoufexis.swim.address.Address.*
import com.stoufexis.swim.members.*
import com.stoufexis.swim.message.*

import scala.annotation.tailrec

object Serde:
  // type used only for serialization/deserialization
  case class Meta(mt: MessageType, from: Address, to: Address) derives Encoder

  /** @return
    *   the bytes and the addresses that were included in the payload after applying the bound.
    */
  def encodeBounded(msg: OutgoingMessage, maxBytes: Int): (Map[Address, MemberState], Chunk[Byte]) =
    val fieldsBytes: Chunk[Byte] = Codec.encode(Meta(msg.typ, msg.from, msg.to))

    val remainingSpace: Int = maxBytes - fieldsBytes.size

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

  def decode(currentAddress: CurrentAddress): Chunk[Byte] => Either[String, IncomingMessage] =
    val destructuredDecoder: Decoder[(Meta, Chunk[Payload])] =
      // Given the current address, we can produce a decoder for any address
      given Decoder[Address] = Decoder.pair[String, Int].map(Address(currentAddress, _, _))
      // Given a decoder for address, we can derive a decoder for meta and payload
      given Decoder[Meta]    = Decoder.derived
      given Decoder[Payload] = Decoder.derived
      Decoder.pair

    bytes =>
      destructuredDecoder.decode(bytes) match
        case None =>
          Left("Decoding error")

        case Some((remainder, _)) if remainder.nonEmpty =>
          Left("Unexpected remainder")

        case Some((_, (Meta(mt, from: RemoteAddress, to: CurrentAddress), payload))) =>
          Right(TerminatingMessage(mt, from, to, payload))

        case Some((_, (Meta(mt, from: RemoteAddress, to: RemoteAddress), payload))) =>
          Right(RedirectMessage(mt, from, to, payload))

        case Some((_, (Meta(_, _: CurrentAddress, _), _))) =>
          Left("Message had the current address in the `from` field")
