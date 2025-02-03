# Intro

This is a library for establishing a dynamic cluster of servers. It is built for ZIO and it uses a variant of the [SWIM (Scalable Weakly-consistent Infection-style Process Group Membership)](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf) gossip protocol.

It provides the `Swim` service, which can be used for dynamically retrieving the addresses of all servers in the cluster, as well as their current status (Alive, Suspicious, Failed).

```scala
trait Swim:
  /**
    * Emits the current memberlist and then subsequent updates.
    */
  def members: UStream[Map[Address, MemberStatus]]
```

# Warning

This project is in WIP status.

# Usage

WIP

# Notes on implementation

## Tick-based

This implementation uses the notion of ticks instead of a local clock. The tick interval is configurable, and all other intervals are defined as multiples of this interval.

## Usage of ZPure

The [ZPure data type of zio-prelude](https://zio.dev/zio-prelude/zpure/) is used heavily to purely model the logic of the internal SWIM state machine

# TODOs

* An anti-entropy mechanism is probably necessary to ensure quick convergence. Inspiration can be taken from [Serf's implementation](https://github.com/hashicorp/serf/blob/master/docs/internals/gossip.html.markdown#swim-modifications)
* Currently, if a node is declared failed without crashing and restarting, it will be declared failed and will be ignored. In these cases the node needs to be forced to go through a new join process.
* A thorough automated test suite should be put in place.
* A final implementation for the Channel service should be provided.