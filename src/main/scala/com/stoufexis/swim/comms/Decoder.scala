package com.stoufexis.swim.comms

import zio.Chunk

import com.stoufexis.swim.*

import scala.deriving.Mirror

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

trait Decoder[A]:
  def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], A)]

  def map[B](f: A => B): Decoder[B] =
    bs => decode(bs).map((rem, a) => (rem, f(a)))

object Decoder:
  inline def derived[A](using m: Mirror.Of[A]): Decoder[A] =
    import scala.compiletime.*

    inline m match
      case s: Mirror.SumOf[A] =>
        val ct = summonAll[Tuple.Map[m.MirroredElemTypes, Decoder]].toArray
        makeEnum(s)(ct.lift(_).asInstanceOf[Option[Decoder[A]]])

      case p: Mirror.ProductOf[A] =>
        val instances = summonAll[Tuple.Map[p.MirroredElemTypes, Decoder]]
        makeProduct(p, summonInline)(instances)

  def makeProduct[T](
    m:  Mirror.ProductOf[T],
    ev: T <:< Product
  )(ts: Tuple.Map[m.MirroredElemTypes, Decoder]): Decoder[T] =
    bytes =>
      val out: Option[(Chunk[Byte], Tuple)] =
        ts.productIterator.foldLeft(Option(bytes, Tuple(): Tuple)): (acc, codec) =>
          for
            (remainder, builder) <- acc
            (remainder, out)     <- codec.asInstanceOf[Decoder[Any]].decode(remainder)
          yield (remainder, builder :* out)

      out.map((rem, tup) => rem -> m.fromTuple(tup.asInstanceOf[m.MirroredElemTypes]))

  def makeEnum[A](m: Mirror.SumOf[A])(tc: Int => Option[Decoder[A]]): Decoder[A] =
    bytes =>
      for
        (remainder, ord) <- int.decode(bytes)
        codec            <- tc(ord)
        (remainder, a)   <- codec.decode(remainder)
      yield (remainder, a)

  given singleton[A](using v: ValueOf[A]): Decoder[A] = bytes =>
    Some((bytes, v.value))

  given int: Decoder[Int] = bytes =>
    Option.when(bytes.size >= 4):
      (bytes.drop(4), ByteBuffer.wrap(bytes.toArray).getInt())

  given pair[A, B](using Decoder[A], Decoder[B]): Decoder[(A, B)] =
    makeProduct(summon[Mirror.ProductOf[(A, B)]], summon)((summon, summon))

  given string: Decoder[String] = bytes =>
    int.decode(bytes).map: (remainder, size) =>
      (remainder.drop(size), String(remainder.take(size).toArray, StandardCharsets.UTF_8))

  given chunk[A](using ca: Decoder[A]): Decoder[Chunk[A]] with
    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], Chunk[A])] =
      for
        (remainder, length) <-
          int.decode(bytes)

        (remainder, out) <-
          (0 until length).foldLeft(Option(remainder, Chunk.empty[A])): (acc, _) =>
            for
              (rem, elems) <- acc
              (rem, a)     <- ca.decode(rem)
            yield (rem, elems :+ a)
      yield (remainder, out)
