package com.stoufexis.swim.util

import zio.*
import zio.stream.*

import scala.collection.immutable.LongMap

/** Inspired by fs2 SignallingRef
  */
trait SignallingRef[A]:
  def set(a: A): UIO[Unit]

  def updates: UStream[A]

object SignallingRef:
  def make[A](init: A): UIO[SignallingRef[A]] =
    case class State(value: A, listeners: LongMap[Queue[A]], maxId: Long):
      def removeListener(id: Long): State =
        copy(listeners = listeners - id)

      def addListener(q: Queue[A]): (Long, State) =
        val nextId = maxId + 1
        nextId -> State(value, listeners + (nextId -> q), nextId)

      def newValue(a: A): State =
        copy(value = a)

    for
      ref: Ref[State] <- Ref.make(State(init, LongMap(), 0L))
    yield new:
      /** An interruption between the execution of modify and the effect offerring to the listener queues
        * would result in a value being incorporated to the state without listeners knowing about it. To
        * prevent that case, we make this sequence of actions uninterruptible
        */
      def set(a: A): UIO[Unit] =
        ref
          .modify(st => ZIO.foreachDiscard(st.listeners)((_, q) => q.offer(a)) -> st.newValue(a))
          .flatten
          .uninterruptible

      def updates: UStream[A] =
        def acquire(q: Queue[A]): UIO[Long] =
          ref.modify(_.addListener(q))

        def release(id: Long): UIO[Unit] =
          ref.update(_.removeListener(id))

        for
          q   <- ZStream.fromZIO(Queue.sliding[A](1))
          _   <- ZStream.acquireReleaseWith(acquire(q))(release)
          out <- ZStream.fromQueue(q)
        yield out
