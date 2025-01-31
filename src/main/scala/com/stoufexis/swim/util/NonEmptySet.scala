package com.stoufexis.swim.util

import zio.*

opaque type NonEmptySet[A] <: Set[A] = Set[A]

object NonEmptySet:
  inline def apply[A](set: Set[A]): Option[NonEmptySet[A]] =
    if set.isEmpty then None else Some(set)

  extension [A](s: NonEmptySet[A])
    def safeHead: A = s.head

    def toNonEmptyVector: NonEmptyVector[A] = NonEmptyVector(s.toVector).get

    def randomElems(cnt: Int): UIO[Iterable[A]] =
      ZIO.randomWith(_.shuffle(s)).map(_.take(cnt))

    def randomElem: UIO[A] =
      ZIO.randomWith(_.nextIntBetween(0, s.size)).map: i =>
        s.drop(i).head
