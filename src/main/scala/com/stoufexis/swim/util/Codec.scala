package com.stoufexis.swim.util

import zio.Chunk

import com.stoufexis.swim.*

import scala.deriving.Mirror

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

trait Codec[A]:
  def encode(a: A): Chunk[Byte]

  def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], A)]

object Codec:
  import scala.compiletime.*

  inline def derived[A](using m: Mirror.Of[A]): Codec[A] =
    inline m match
      case s: Mirror.SumOf[A] =>
        val ct = summonAll[Tuple.Map[s.MirroredElemTypes, Codec]].toArray
        makeEnum(s)(ct.lift(_).asInstanceOf[Option[Codec[A]]])

      case p: Mirror.ProductOf[A] =>
        val ct = summonInline[Codec[p.MirroredElemTypes]]
        makeCaseClass(p)(using ct, summonInline)

  def makeEnum[A](m: Mirror.SumOf[A])(tc: Int => Option[Codec[A]]): Codec[A] =
    new:
      def encode(a: A): Chunk[Byte] =
        val ord = m.ordinal(a)
        summon[Codec[Int]].encode(ord) ++ tc(ord).get.encode(a)

      def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], A)] =
        for
          (remainder, ord) <- summon[Codec[Int]].decode(bytes)
          codec            <- tc(ord)
          (remainder, a)   <- codec.decode(remainder)
        yield (remainder, a)

  def makeCaseClass[A](m: Mirror.ProductOf[A])(using
    tc:   Codec[m.MirroredElemTypes],
    cast: A <:< Product
  ): Codec[A] =
    new:
      def encode(a: A): Chunk[Byte] =
        tc.encode(Tuple.fromProduct(cast(a)).asInstanceOf[m.MirroredElemTypes])

      def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], A)] =
        tc.decode(bytes).map: (remainder, tup) =>
          (remainder, m.fromTuple(tup))

  extension [A](ca: Codec[A])
    def bimap[B](f: B => A, g: A => Option[B]): Codec[B] = new:
      def encode(a: B): Chunk[Byte] = ca.encode(f(a))

      def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], B)] =
        for
          (remainder, a) <- ca.decode(bytes)
          b              <- g(a)
        yield (remainder, b)


  given [A](using v: ValueOf[A]): Codec[A] = new:
    def encode(a: A): Chunk[Byte] = Chunk()

    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], A)] = Some((bytes, v.value))

  given Codec[Int] with
    def encode(a: Int): Chunk[Byte] =
      Chunk.fromArray(ByteBuffer.allocate(4).putInt(a).array())

    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], Int)] =
      Option.when(bytes.size >= 4):
        (bytes.drop(4), ByteBuffer.wrap(bytes.toArray).getInt())

  given Codec[String] with
    def encode(a: String): Chunk[Byte] =
      val bytes: Array[Byte] = a.getBytes(StandardCharsets.UTF_8)
      val bb:    ByteBuffer  = ByteBuffer.allocate(4 + bytes.length)
      bb.putInt(bytes.length)
      bb.put(bytes)
      Chunk.fromArray(bb.array())

    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], String)] =
      Option.when(bytes.size >= 4):
        val length    = ByteBuffer.wrap(bytes.take(4).toArray).getInt()
        val strBuffer = bytes.slice(4, 4 + length)
        (bytes.drop(4 + length), String(strBuffer.toArray, StandardCharsets.UTF_8))

  given Codec[EmptyTuple] = new:
    def encode(a: EmptyTuple): Chunk[Byte] = Chunk()

    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], EmptyTuple)] = Some((bytes, EmptyTuple))

  given [H, T <: Tuple](using ca: Codec[H], cb: Codec[T]): Codec[H *: T] with
    def encode(a: (H *: T)): Chunk[Byte] =
      ca.encode(a.head) ++ cb.encode(a.tail)

    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], (H *: T))] =
      for
        (remainderA, a) <- ca.decode(bytes)
        (remainderB, b) <- cb.decode(remainderA)
      yield (remainderB, a *: b)

  given [A](using ca: Codec[A]): Codec[Chunk[A]] with
    def encode(a: Chunk[A]): Chunk[Byte] =
      summon[Codec[Int]].encode(a.size) ++ a.flatMap(ca.encode)

    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], Chunk[A])] =
      for
        (remainder, length) <-
          summon[Codec[Int]].decode(bytes)

        (remainder, out) <-
          List.range(0, length).foldLeft(Option((remainder, Chunk.empty[A]))): (acc, _) =>
            for
              (rem, elems) <- acc
              (rem, a)     <- ca.decode(rem)
            yield (rem, elems.appended(a))
      yield (remainder, out)

  given [A, B](using ca: Codec[A], cb: Codec[B]): Codec[Map[A, B]] =
    summon[Codec[Chunk[(A, B)]]].bimap(Chunk.from(_), a => Some(Map.from(a)))