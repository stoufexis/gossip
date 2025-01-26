package com.stoufexis.swim.comms

import zio.Chunk

import com.stoufexis.swim.*

import scala.deriving.Mirror

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

trait Encoder[A]:
  def encode(a: A): Chunk[Byte]

object Encoder:
  inline def derived[A](using m: Mirror.Of[A]): Encoder[A] =
    import scala.compiletime.*

    inline m match
      case s: Mirror.SumOf[A] =>
        val ct = summonAll[Tuple.Map[m.MirroredElemTypes, Encoder]].toArray
        makeEnum(s)(ct.lift(_).asInstanceOf[Option[Encoder[A]]])

      case p: Mirror.ProductOf[A] =>
        val instances = summonAll[Tuple.Map[p.MirroredElemTypes, Encoder]]
        makeProduct(p, summonInline)(instances)

  def makeProduct[T](
    m:  Mirror.ProductOf[T],
    ev: T <:< Product
  )(ts: Tuple.Map[m.MirroredElemTypes, Encoder]): Encoder[T] =
    a =>
      Tuple.fromProduct(ev(a)).zip(ts).productIterator.foldLeft(Chunk.empty): (acc, x) =>
        val (elem, codec) = x.asInstanceOf[(Any, Encoder[Any])]
        acc ++ codec.encode(elem)

  def makeEnum[A](m: Mirror.SumOf[A])(tc: Int => Option[Encoder[A]]): Encoder[A] =
    a =>
      val ord = m.ordinal(a)
      int.encode(ord) ++ tc(ord).get.encode(a)

  given singleton[A](using v: ValueOf[A]): Encoder[A] = _ => Chunk()

  given int: Encoder[Int] = a =>
    Chunk.fromArray(ByteBuffer.allocate(4).putInt(a).array())

  given string: Encoder[String] = a =>
    val bytes: Array[Byte] = a.getBytes(StandardCharsets.UTF_8)
    int.encode(bytes.length) ++ Chunk.fromArray(bytes)

  given chunk[A](using ca: Encoder[A]): Encoder[Chunk[A]] = a =>
    int.encode(a.size) ++ a.flatMap(ca.encode)
