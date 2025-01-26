package com.stoufexis.swim.comms

import zio.Chunk

import com.stoufexis.swim.*

trait Codec[A] extends Encoder[A] with Decoder[A]

object Codec:
  def apply[A](encoder: Encoder[A], decoder: Decoder[A]): Codec[A] = new:
    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], A)] = decoder.decode(bytes)
    def encode(a: A): Chunk[Byte] = encoder.encode(a)

  def encode[A: Encoder](a: A): Chunk[Byte] =
    summon[Encoder[A]].encode(a)

  def decode[A: Decoder](bytes: Chunk[Byte]): Option[(Chunk[Byte], A)] =
    summon[Decoder[A]].decode(bytes)

  inline def derived[A]: Codec[A] =
    import scala.compiletime.*
    Codec(Encoder.derived(using summonInline), Decoder.derived(using summonInline))

  extension [A](ca: Codec[A])
    def bimap[B](f: B => A, g: A => Option[B]): Codec[B] = new:
      def encode(a: B): Chunk[Byte] = ca.encode(f(a))
      def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], B)] =
        for
          (remainder, a) <- ca.decode(bytes)
          b              <- g(a)
        yield (remainder, b)