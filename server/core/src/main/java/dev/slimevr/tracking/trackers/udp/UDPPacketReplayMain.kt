package dev.slimevr.tracking.trackers.udp

import java.io.File

fun main(args: Array<String>) {
	if (args.size != 1) {
		println("Usage: java -cp slimevr.jar dev.slimevr.tracking.trackers.udp.UDPPacketReplayMainKt <tracker-packets.dat>")
		return
	}

	val file = File(args[0])
	if (!file.exists()) {
		println("File $file does not exist")
		return
	}

	val replay = UDPPacketReplay(file)
	replay.start()
}
