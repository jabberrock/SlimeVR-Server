package dev.slimevr.tracking.videocalibration.human

import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.outputStream

object RTMPoseModel {

	private val lock = Any()
	private var loaded = false
	private lateinit var tempFile: Path

	fun modelPath(): Path {
		synchronized(lock) {
			if (!loaded) {
				tempFile = copyToTempFile()
				loaded = true
			}
			return tempFile
		}
	}

	private fun copyToTempFile(): Path {
		val resource = this::class.java.getResourceAsStream(MODEL_PATH)
			?: error("Missing RTMPose model at $MODEL_PATH")

		val tempFile = createTempFile(prefix = MODEL_TEMP_FILE_PREFIX, suffix = MODEL_TEMP_FILE_SUFFIX)
		tempFile.outputStream().use { resource.copyTo(it) }
		tempFile.toFile().deleteOnExit()

		return tempFile
	}

	private const val MODEL_PATH = "/rtmpose-m_simcc-body7_pt-body7-halpe26_700e-256x192-4d3e73dd_20230605.onnx"

	private const val MODEL_TEMP_FILE_PREFIX = "rtmpose-"
	private const val MODEL_TEMP_FILE_SUFFIX = ".onnx"
}
