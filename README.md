# Notes

* I need to come up with a mechanism for ensuring dissemination of old but still relevant events. 
  Something like a periodic full state update (anti entropy mechanism).
* I need to only keep the "latest" state update of each cluster node. 
  This requires either a notion of message ordering within the cluster, or modelling the state as a CRDT in some other fashion.