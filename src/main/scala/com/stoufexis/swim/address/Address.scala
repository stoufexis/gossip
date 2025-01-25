package com.stoufexis.swim.address

import com.stoufexis.swim.comms.Codec

case class Address(host: String, port: Int) derives Codec