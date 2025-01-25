package com.stoufexis.swim.comms

import zio.Chunk

import com.stoufexis.swim.*

import scala.deriving.Mirror

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

trait Codec[A]:
  def encode(a: A): Chunk[Byte]

  def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], A)]

object Codec:
  inline def encode[A: Codec](a: A): Chunk[Byte] =
    summon[Codec[A]].encode(a)

  inline def decode[A: Codec](bytes: Chunk[Byte]): Option[(Chunk[Byte], A)] =
    summon[Codec[A]].decode(bytes)

  inline def derived[A](using m: Mirror.Of[A]): Codec[A] =
    import scala.compiletime.*

    inline m match
      case s: Mirror.SumOf[A] =>
        val ct = summonAll[Tuple.Map[m.MirroredElemTypes, Codec]].toArray
        makeEnum(s)(ct.lift(_).asInstanceOf[Option[Codec[A]]])

      case p: Mirror.ProductOf[A] =>
        val instances = summonAll[Tuple.Map[p.MirroredElemTypes, Codec]]
        makeProduct(p, summonInline)(instances)

  def makeProduct[T](m: Mirror.ProductOf[T], ev: T <:< Product)(ts: Tuple.Map[m.MirroredElemTypes, Codec]): Codec[T] = 
    new:
      def encode(a: T): Chunk[Byte] =
        Tuple.fromProduct(ev(a)).zip(ts).productIterator.foldLeft(Chunk.empty): (acc, x) =>
          val (elem, codec) = x.asInstanceOf[(Any, Codec[Any])]
          acc ++ codec.encode(elem)

      def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], T)] =
        val out: Option[(Chunk[Byte], Tuple)] =
          ts.productIterator.foldLeft(Option(bytes, Tuple(): Tuple)): (acc, codec) =>
            for
              (remainder, builder) <- acc
              (remainder, out)     <- codec.asInstanceOf[Codec[Any]].decode(remainder)
            yield (remainder, builder :* out)

        out.map((rem, tup) => rem -> m.fromTuple(tup.asInstanceOf[m.MirroredElemTypes]))

  def makeEnum[A](m: Mirror.SumOf[A])(tc: Int => Option[Codec[A]]): Codec[A] =
    new:
      def encode(a: A): Chunk[Byte] =
        val ord = m.ordinal(a)
        int.encode(ord) ++ tc(ord).get.encode(a)

      def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], A)] =
        for
          (remainder, ord) <- int.decode(bytes)
          codec            <- tc(ord)
          (remainder, a)   <- codec.decode(remainder)
        yield (remainder, a)

  given singleton[A](using v: ValueOf[A]): Codec[A] = new:
    def encode(a: A): Chunk[Byte] = Chunk()

    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], A)] = Some((bytes, v.value))

  given int: Codec[Int] with
    def encode(a: Int): Chunk[Byte] =
      Chunk.fromArray(ByteBuffer.allocate(4).putInt(a).array())

    def decode(bytes: Chunk[Byte]): Option[(Chunk[Byte], Int)] =
      Option.when(bytes.size >= 4):
        (bytes.drop(4), ByteBuffer.wrap(bytes.toArray).getInt())

  given string: Codec[String] with
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

  given chunk[A](using ca: Codec[A]): Codec[Chunk[A]] with
    def encode(a: Chunk[A]): Chunk[Byte] =
      int.encode(a.size) ++ a.flatMap(ca.encode)

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