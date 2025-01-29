package com.stoufexis.swim.model

import com.stoufexis.swim.model.Address.RemoteAddress
import com.stoufexis.swim.comms.*

// TODO: Rename to Updates and rename fields
case class Payload(add: RemoteAddress, ms: MemberState) derives Encoder
