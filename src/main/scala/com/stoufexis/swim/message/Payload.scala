package com.stoufexis.swim.message

import com.stoufexis.swim.address.Address.RemoteAddress
import com.stoufexis.swim.comms.*
import com.stoufexis.swim.members.MemberState

// TODO: Rename to Updates and rename fields
case class Payload(add: RemoteAddress, ms: MemberState) derives Encoder
