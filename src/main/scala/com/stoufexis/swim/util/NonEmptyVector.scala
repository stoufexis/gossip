package com.stoufexis.swim.util

opaque type NonEmptyVector[A] <: Vector[A] = Vector[A]

object NonEmptyVector:
  inline def apply[A](set: Vector[A]): Option[NonEmptyVector[A]] =
    if set.isEmpty then None else Some(set)

  extension [A](v: NonEmptyVector[A])
    def safeHead: A = v.head
