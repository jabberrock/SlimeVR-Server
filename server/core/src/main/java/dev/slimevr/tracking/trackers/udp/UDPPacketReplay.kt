package dev.slimevr.tracking.trackers.udp

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

class UDPPacketReplay(val file: File) {

	private var nextSocketPort = 18000;

	fun start() {
		var startTimeInNs = 0L
		val startTime = TimeSource.Monotonic.markNow()

	    val stream = DataInputStream(FileInputStream(file))
		val deviceSockets = mutableMapOf<InetSocketAddress, DatagramSocket>()

		try {
			while (true) {
				val timestampInNs = stream.readLong()
				if (startTimeInNs == 0L) {
					startTimeInNs = timestampInNs
				}

				// Wait until it's time to send the packet
				while (true) {
					val now = TimeSource.Monotonic.markNow()
					val nowInNs = now.minus(startTime).toLong(DurationUnit.NANOSECONDS)
					if (nowInNs > timestampInNs - startTimeInNs) {
						break
					}

					Thread.sleep(1L)
				}

				val ip = stream.readNBytes(4)
				val port = stream.readShort().toInt()
				val address = InetSocketAddress(InetAddress.getByAddress(ip), port)

				val packetLength = stream.readInt()
				val packetData = stream.readNBytes(packetLength)

				var socket = deviceSockets[address]
				if (socket == null) {
					socket = DatagramSocket(nextSocketPort++)
					deviceSockets[address] = socket
				}

				socket.send(DatagramPacket(packetData, packetLength, InetAddress.getLoopbackAddress(), SLIMEVR_SERVER_PORT))
			}
		} catch (e: EOFException) {
			// Do nothing
		}
	}

	companion object {

		private const val SLIMEVR_SERVER_PORT = 6969;
	}
}
