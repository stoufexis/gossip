package com.stoufexis.swim.model

import com.stoufexis.swim.comms.Encoder

case class Update(address: Address, status: MemberStatus, incarnation: Int) derives Encoder
