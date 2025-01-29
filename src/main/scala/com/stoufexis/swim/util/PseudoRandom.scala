package com.stoufexis.swim.util

import zio.*

// TODO: Verify validity
/** A pure pseudo-random number generator.
  *
  * Each method of each PseudoRandom object is referentially transparent, as it always returns the same value
  * given the same parameters, along with the same next PseudoRandom instance, containing a new seed.
  */
trait PseudoRandom:
  def nextInt(maxExclusive: Int): (Int, PseudoRandom)

  def shuffle[A](iterable: Iterable[A]): (Iterable[A], PseudoRandom)

object PseudoRandom:
  case class Impl(seed: Long) extends PseudoRandom:
    def newSeed: Long =
      (seed * 0x5deece66dL + 0xbL) & ((1L << 48) - 1)

    def nextInt(maxExclusive: Int): (Int, PseudoRandom) =
      (scala.util.Random(seed).nextInt(maxExclusive), Impl(newSeed))

    def shuffle[A](iterable: Iterable[A]): (Iterable[A], PseudoRandom) =
      (scala.util.Random(seed).shuffle(iterable), Impl(newSeed))

  def make: UIO[PseudoRandom] =
    ZIO.randomWith(_.nextLong.map(Impl(_)))
