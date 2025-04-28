package dev.slimevr.tracking.trackers.udp

import dev.slimevr.SLIMEVR_IDENTIFIER
import io.eiren.util.OperatingSystem
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

class UDPPacketLogger(file: File) {

	private val stream: DataOutputStream = DataOutputStream(FileOutputStream(file))
	private val startTime = TimeSource.Monotonic.markNow()

	fun add(packet: DatagramPacket) {
		val now = TimeSource.Monotonic.markNow()
		stream.writeLong(now.minus(startTime).toLong(DurationUnit.NANOSECONDS))
		stream.write(packet.address.address)
		stream.writeShort(packet.port)
		stream.writeInt(packet.length)
		stream.write(packet.data, 0, packet.length)
	}

	companion object {

		private const val MAX_FILES = 4;
		private const val FILE_PREFIX = "tracker-packets-"
		private const val DATE_TIME_FORMAT = "yyyy-MM-dd_HH-mm-ss"

		fun createNew(): UDPPacketLogger? {
			val dir = OperatingSystem.resolveLogDirectory(SLIMEVR_IDENTIFIER) ?: return null

			val packetFiles =
				Files.walk(dir, 1)
					.filter { it.fileName.toString().startsWith(FILE_PREFIX) }
					.sorted(Comparator.reverseOrder())
					.toList()

			if (packetFiles.size > MAX_FILES) {
				for (i in MAX_FILES - 1 until packetFiles.size) {
					Files.delete(packetFiles[i])
				}
			}

			val time = DateTimeFormatter.ofPattern(DATE_TIME_FORMAT).format(LocalDateTime.now())
			val path = dir.resolve("${FILE_PREFIX}${time}.dat")

			return UDPPacketLogger(path.toFile())
		}
	}
}
