package com.stoufexis.swim.util

import zio.*

// TODO: Check validity
class PseudoRandom(seed: Long):
  def newSeed: Long =
    (seed * 0x5deece66dL + 0xbL) & ((1L << 48) - 1)

  def nextInt(maxExclusive: Int): (Int, PseudoRandom) =
    (scala.util.Random(seed).nextInt(maxExclusive), PseudoRandom(newSeed))

  def shuffle[A](iterable: Iterable[A]): (Iterable[A], PseudoRandom) =
    (scala.util.Random(seed).shuffle(iterable), PseudoRandom(newSeed))

object PseudoRandom:
  def make: UIO[PseudoRandom] =
    ZIO.randomWith(_.nextLong.map(PseudoRandom(_)))