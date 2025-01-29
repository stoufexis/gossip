package com.stoufexis.swim.util

import zio.*

// TODO: Verify validity
/** A pure pseudo-random number generator.
  *
  * Each method of each PseudoRandom object is referentially transparent, as it always returns the same value
  * given the same parameters, along with the same next PseudoRandom instance, containing a new seed.
  */
class PseudoRandom(seed: Long):
  private def newSeed: Long =
    (seed * 0x5deece66dL + 0xbL) & ((1L << 48) - 1)

  def nextInt(maxExclusive: Int): (Int, PseudoRandom) =
    (scala.util.Random(seed).nextInt(maxExclusive), PseudoRandom(newSeed))

  def shuffle[A](iterable: Iterable[A]): (Iterable[A], PseudoRandom) =
    (scala.util.Random(seed).shuffle(iterable), PseudoRandom(newSeed))

object PseudoRandom:
  def make: UIO[PseudoRandom] =
    ZIO.randomWith(_.nextLong.map(PseudoRandom(_)))