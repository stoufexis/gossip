package com.stoufexis.swim.address

import com.stoufexis.swim.util.Codec

case class Address(host: String, port: Int) derives Codec