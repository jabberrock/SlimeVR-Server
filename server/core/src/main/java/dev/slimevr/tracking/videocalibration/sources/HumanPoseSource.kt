package dev.slimevr.tracking.videocalibration.sources

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.slimevr.tracking.videocalibration.data.CocoWholeBodyKeypoint
import dev.slimevr.tracking.videocalibration.snapshots.HumanPoseSnapshot
import dev.slimevr.tracking.videocalibration.snapshots.ImageSnapshot
import dev.slimevr.tracking.videocalibration.util.DebugOutput
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.Vector2D
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

class HumanPoseSource(
	val imagesSource: Channel<ImageSnapshot>,
	val debugOutput: DebugOutput,
) {

	enum class Status {
		NOT_RUNNING,
		RUNNING,
		DONE,
	}

	val status = AtomicReference(Status.NOT_RUNNING)
	val humanPoseSnapshots = Channel<HumanPoseSnapshot>(Channel.Factory.UNLIMITED)

	private var poseCount = 0

	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	fun start() {
		scope.launch {
			status.set(Status.RUNNING)

			try {
				for (imageFrame in imagesSource) {
					val imageCount = poseCount
					val humanPose = runPoseDetection(imageCount, imageFrame)
					if (humanPose != null && humanPose.joints.isNotEmpty()) {
						humanPoseSnapshots.trySend(humanPose)
// 						LogManager.debug("Recovered human pose from image")

						debugOutput.saveHumanPoseImage(
							imageFrame.timestamp,
							imageFrame.image,
							humanPose,
						)

						++poseCount
					}
				}
			} catch (e: Exception) {
				LogManager.warning("Human pose source failed", e)
			} finally {
				status.set(Status.DONE)
			}
		}
	}

	fun requestStop() {
		scope.cancel()
	}

	private fun runPoseDetection(imageCount: Int, imageSnapshot: ImageSnapshot): HumanPoseSnapshot? {
		val image = imageSnapshot.image

		val json: String
		try {
			json = requestRtmposeJson(image)
		} catch (e: Exception) {
			LogManager.warning("Failed to run human pose detection on image $imageCount: $e")
			return null
		}

		val result: Map<CocoWholeBodyKeypoint, Vector2D>?
		try {
			result = parseSingle(json)
		} catch (e: Exception) {
			LogManager.warning("Failed to parse human pose detection response on image $imageCount: $e")
			return null
		}

		if (result == null) {
			return null
		}

		return HumanPoseSnapshot(
			imageSnapshot.instant,
			imageSnapshot.timestamp,
			result,
			imageSnapshot.camera,
		)
	}

	/**
	 * Sends an image to RTMPose HTTP service and returns raw JSON response.
	 *
	 * Service endpoint example: http://127.0.0.1:15524/rtmpose
	 */
	@Throws(RuntimeException::class)
	fun requestRtmposeJson(
		frame: BufferedImage,
		endpoint: String = "http://127.0.0.1:15524/rtmpose",
		imageName: String = "frame.jpg",
		timeoutMillis: Long = 1000,
	): String {
		require(timeoutMillis > 0) { "timeoutMillis must be > 0." }

		val imageBytes = ByteArrayOutputStream().use {
			ImageIO.write(frame, "jpg", it)
			it.toByteArray()
		}

		val client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofMillis(timeoutMillis))
			.build()

		val request = HttpRequest.newBuilder()
			.uri(URI.create(endpoint))
			.timeout(Duration.ofMillis(timeoutMillis))
			.header("Content-Type", "image/jpg")
			.header("X-Image-Name", imageName)
			.POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
			.build()

		val response = try {
			client.send(request, HttpResponse.BodyHandlers.ofString())
		} catch (e: Exception) {
			throw RuntimeException("Failed to call RTMPose service at $endpoint: $e", e)
		}

		if (response.statusCode() !in 200..299) {
			throw RuntimeException(
				"RTMPose service returned HTTP ${response.statusCode()}: ${response.body()}",
			)
		}
		return response.body()
	}

	class Joint(
		val id: Int,
		val name: String,
		val x: Double,
		val y: Double,
		val confidence: Double,
		val visible: Boolean,
	)

	class HumanPose(
		@JsonProperty("person_id")
		val personId: Int,
		val joints: List<Joint>,
	)

	class PoseDetectionResult(
		@JsonProperty("image_path")
		val imagePath: String,
		val detections: List<HumanPose>,
	)

	class VideoPoseDetectionResult(
		@JsonProperty("video_path")
		val videoPath: String?,
		@JsonProperty("overlay_video_path")
		val overlayVideoPath: String?,
		val frames: List<PoseDetectionResult>,
	)

	fun parseSingle(json: String): Map<CocoWholeBodyKeypoint, Vector2D>? {
		val poseMapper = jacksonObjectMapper()
		val parsed = try {
			poseMapper.readValue(json, PoseDetectionResult::class.java)
		} catch (e: Exception) {
			throw RuntimeException("Failed to parse pose JSON: $e", e)
		}

		return parseResult(parsed)
	}

	private fun parseResult(result: PoseDetectionResult): Map<CocoWholeBodyKeypoint, Vector2D>? {
		val firstPerson = result.detections.firstOrNull() ?: return null

		val orderedKeys = CocoWholeBodyKeypoint.entries.toTypedArray()
		val joints = firstPerson.joints
			.asSequence()
			.mapNotNull { joint ->
				val keypoint = orderedKeys.getOrNull(joint.id) ?: return@mapNotNull null
				keypoint to Vector2D(joint.x, joint.y)
			}
			.toMap()

		return joints
	}
}
