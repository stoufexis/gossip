package com.stoufexis.swim

// import zio.*

// import com.stoufexis.swim.address.*
// import com.stoufexis.swim.address.Address.*
// import com.stoufexis.swim.members.*
// import com.stoufexis.swim.message.*
// import com.stoufexis.swim.comms.*

// object Main extends App:
//   val m1: TerminatingMessage =
//     TerminatingMessage(
//       MessageType.Ping,
//       RemoteAddress.unsafe(Address("remote1", 4269)),
//       CurrentAddress.unsafe(Address("current1", 6942)),
//       Chunk(Payload(Address("local", 111), MemberState.Failed))
//     )

//   val m2: RedirectMessage =
//     RedirectMessage(
//       MessageType.Join,
//       RemoteAddress.unsafe(Address("remote2", 4269)),
//       RemoteAddress.unsafe(Address("remote2", 6942)),
//       Chunk()
//     )

  // val codec = summon[Codec[Chunk[IncomingMessage]]]

  // val encoded: Chunk[Byte] = codec.encode(Chunk(m1, m2))
  // val (remainder, decoded) = codec.decode(encoded).get

  // println("ENCODED " + encoded.length)
  // println("REMAINDER " + remainder.length)
  // println("DECODED " + decoded)
