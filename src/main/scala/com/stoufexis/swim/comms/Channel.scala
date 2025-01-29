package com.stoufexis.swim.comms

import zio.*

import com.stoufexis.swim.model.Address.RemoteAddress

// import java.net.InetSocketAddress
// import java.nio.ByteBuffer
// import java.nio.channels.DatagramChannel

trait Channel:
  /** Gets currently available messages from the input buffer. Returns immediatelly if there are none.
    */
  def receive: Task[Chunk[Byte]]

  /** Backpressures if there is no room to output.
    */
  def send(to: RemoteAddress, message: Chunk[Byte]): Task[Unit]

// object Comms:
//   val live: RLayer[Scope & SwimConfig, Comms] = ZLayer:
//     def inet(addr: Address): InetSocketAddress =
//       InetSocketAddress(addr.host, addr.port)

//     for
//       cfg: SwimConfig <-
//         ZIO.service[SwimConfig]

//       ch: DatagramChannel <-
//         ZIO.acquireRelease(ZIO.attemptBlocking(DatagramChannel.open())): ch =>
//           ZIO.succeedBlocking(ch.close())

//       _ <-
//         ZIO.attemptBlocking(ch.bind(inet(cfg.address))) // TODO: This might not be blocking
//     yield new:
//       def receive: Task[(Address, Chunk[Byte])] =
//         val buf  = ByteBuffer.allocate(cfg.receiveBufferSize)
//         val addr = ZIO.attemptBlocking(ch.receive(buf))

//         addr.flatMap:
//           case i: InetSocketAddress =>
//             // TODO: Could avoid a copy here by using Chunk.array
//             ZIO.succeed(Address(i.getHostName(), i.getPort()), Chunk.fromByteBuffer(buf))

//           case a =>
//             ZIO.fail(RuntimeException(s"Unknown address type: $a"))

//       def send(address: Address, chunk: Chunk[Byte]): Task[Unit] =
//         ZIO
//           .attemptBlocking(ch.send(ByteBuffer.wrap(chunk.toArray), inet(address)))
//           .ignore
