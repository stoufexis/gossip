package com.stoufexis.swim.comms

import zio.*

import com.stoufexis.swim.model.Address.*

import scala.annotation.tailrec
import com.stoufexis.swim.model.*

object Serde:
  // type used only for serialization/deserialization
  case class Meta(mt: MessageType, from: Address, to: Address) derives Encoder

  def encodeUnbounded(msg: OutgoingMessage): Chunk[Byte] =
    Codec.encode(msg)

  /** @return
    *   the bytes and the updates that were included in the payload after applying the bound.
    */
  def encodeBounded(msg: OutgoingMessage, maxBytes: Int): (Set[RemoteAddress], Chunk[Byte]) =
    val fieldsBytes: Chunk[Byte] = Codec.encode(Meta(msg.typ, msg.from, msg.to))

    val remainingSpace: Int = maxBytes - fieldsBytes.size

    /**
      * Keeping bytes as a chunk of chunks will ensure that the length of the chunk
      * will be added as the first 4 bytes, as the Chunk encoder will be used.
      */
    @tailrec
    def loop(
      remainder:     Chunk[Payload],
      included:      Set[RemoteAddress],
      bytes:         Chunk[Chunk[Byte]]
    ): (Set[RemoteAddress], Chunk[Chunk[Byte]]) =
      if remainder.isEmpty then
        (included, bytes)
      else
        val next = remainder.head
        val bs   = Codec.encode(next)
        if bytes.size + bs.size > remainingSpace then
          (included, bytes)
        else
          loop(remainder.tail, included + next.add, bytes.appended(bs))

    val (includedAdds, bytes) = loop(msg.payload, Set(), Chunk())
    (includedAdds, fieldsBytes ++ Codec.encode(bytes))

  def decodeAll(currentAddress: CurrentAddress): Chunk[Byte] => Either[String, Chunk[IncomingMessage]] =
    val destructuredDecoder: Decoder[(Meta, Chunk[Payload])] =
      // Given the current address, we can produce a decoder for any address
      given add: Decoder[Address] = Decoder.pair[String, Int].map(Address(currentAddress, _, _))
      given Decoder[RemoteAddress] = add.omap(_.remote)

      // Given a decoder for address, we can derive a decoder for meta and payload
      given Decoder[Meta]    = Decoder.derived
      given Decoder[Payload] = Decoder.derived
      Decoder.pair

    // The default Chunk codec expects the length of the chunk to be written in the first 4 bytes.
    // However, here we want to decode the entire input into a chunk, expecting the length to not be written
    @tailrec
    def loop(remainder: Chunk[Byte], acc: Chunk[IncomingMessage]): Either[String, Chunk[IncomingMessage]] =
      if remainder.isEmpty then
        Right(acc)
      else
        destructuredDecoder.decode(remainder) match
          case None =>
            Left("Decoding error")

          case Some((remainder, (Meta(mt, from: RemoteAddress, to: CurrentAddress), payload))) =>
            loop(remainder, acc :+ TerminatingMessage(mt, from, to, payload))

          case Some((remainder, (Meta(mt, from: RemoteAddress, to: RemoteAddress), payload))) =>
            loop(remainder, acc :+ RedirectMessage(mt, from, to, payload))

          case Some((_, (Meta(_, _: CurrentAddress, _), _))) =>
            Left("Message had the current address in the `from` field")

    bytes => loop(bytes, Chunk())
