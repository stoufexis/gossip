package com.stoufexis.swim.model

import com.stoufexis.swim.comms.*
import com.stoufexis.swim.model.Address.*

/** To reduce the code and mental overhead of always having to check whether an address represents the current
  * node or a different node, these types are introduced. They allow for only checking a single time, and
  * marking the result of the check in the type system, allowing other functions to explicitly request the
  * type of address they require.
  *
  * à la
  * [[Lightweight Static Guarantees https://okmij.org/ftp/Computation/lightweight-static-guarantees.html]]
  */
sealed trait Address derives Encoder:
  val host: String
  val port: Int

  def remote: Option[RemoteAddress] = this match
    case r: RemoteAddress => Some(r)
    case _ => None
  

object Address:
  /**
    * Requires evidence of the current address
    */
  def apply(currentAddress: CurrentAddress, host: String, port: Int): Address =
    if currentAddress.host == host && currentAddress.port == port
    then CurrentAddress(host, port)
    else RemoteAddress(host, port)

  // The following variants of address do not derive a Decoder and dissallow access to the constructor.
  // This means that the only way to create objects of this type is via the safe `apply` method
  case class RemoteAddress private[Address] (host: String, port: Int) extends Address derives Encoder

  case class CurrentAddress private[Address] (host: String, port: Int) extends Address derives Encoder
