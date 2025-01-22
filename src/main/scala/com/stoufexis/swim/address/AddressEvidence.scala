package com.stoufexis.swim.address

/** To reduce the code and mental overhead of always having to check whether an address represents the current
  * node or a different node, this type, along with RemoteAddress are introduced. They allow for only checking
  * a single time, and marking the result of the check in the type system, allowing other functions to
  * explicitly request the type of address they require.
  *
  * à la
  * [[Lightweight Static Guarantees https://okmij.org/ftp/Computation/lightweight-static-guarantees.html]]
  */
opaque type CurrentAddress <: Address = Address

opaque type RemoteAddress <: Address = Address

object CurrentAddress:
  /** Assert that the provided address is the current one.
    */
  inline def unsafe(addr: Address): CurrentAddress = addr

  extension (ca: CurrentAddress)
    /** Provides evidence that the address is either remote or current
      */
    def check(addr: Address): Either[RemoteAddress, CurrentAddress] =
      if ca == addr then Right(addr) else Left(addr)

    def isCurrent(addr: Address): Boolean =
      check(addr).isRight
