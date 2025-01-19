package com.stoufexis.swim

import zio.*

trait Comms:
  /** Gets currently available messages from the input buffer
    */
  def receive: Task[Chunk[Message]]

  def send(to: Address, message: Message): Task[Unit]

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
//         ZIO.attemptBlocking(ch.bind(inet(cfg.bindAddress))) // TODO: This might not be blocking
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
