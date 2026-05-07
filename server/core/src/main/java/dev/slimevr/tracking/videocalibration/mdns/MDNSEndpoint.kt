package dev.slimevr.tracking.videocalibration.mdns

import java.net.InetAddress

data class MDNSEndpoint(
	val serviceType: String,
	val inetAddress: InetAddress,
	val port: Int,
)
