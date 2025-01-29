# Notes

* I need to come up with a mechanism for ensuring dissemination of old but still relevant events. Something like a periodic full state update (anti entropy mechanism).
* Currently, if a node is declared failed but it does not restart and rejoin, it will simply not be part of the gossip nodes but will not know about it. Some mechanism needs to be put in place to force the node to rejoin properly
* If we are waiting for an ack from a server and we learn that it failed via gossip, we stop waiting for its ack