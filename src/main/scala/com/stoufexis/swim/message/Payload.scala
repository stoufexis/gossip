package com.stoufexis.swim.message

import com.stoufexis.swim.address.Address
import com.stoufexis.swim.members.MemberState
import com.stoufexis.swim.comms.*

case class Payload(add: Address, ms: MemberState) derives Encoder