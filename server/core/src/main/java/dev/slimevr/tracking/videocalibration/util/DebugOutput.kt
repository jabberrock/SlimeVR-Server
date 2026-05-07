package dev.slimevr.tracking.videocalibration.util

import dev.slimevr.SLIMEVR_IDENTIFIER
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.human.HumanJoint
import dev.slimevr.tracking.videocalibration.human.HumanPoseSnapshot
import dev.slimevr.tracking.videocalibration.vision.Camera
import io.eiren.util.OperatingSystem
import io.github.axisangles.ktmath.QuaternionD
import io.github.axisangles.ktmath.Vector2D
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics2D
import java.awt.Stroke
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectory
import kotlin.time.Duration

class DebugOutput(
	dir: Path,
) {
	val cameraFile: Path
	val webcamDir: Path
	val humanPosesDir: Path
	val reconstructionDir: Path
	val skeletonOffsetsDir: Path
	val trackersFile: Path
	val cameraAlignmentImage: Path
	val reconstructionVideo: Path
	val skeletonOffsetsVideo: Path

	init {
		dir.toFile().deleteRecursively()
		dir.createDirectory()

		cameraFile = dir.resolve("camera.txt")

		webcamDir = dir.resolve("1_webcam")
		webcamDir.createDirectory()

		humanPosesDir = dir.resolve("2_poses")
		humanPosesDir.createDirectory()

		reconstructionDir = dir.resolve("3_reconstruction")
		reconstructionDir.createDirectory()

		skeletonOffsetsDir = dir.resolve("4_skeleton_offsets")
		skeletonOffsetsDir.createDirectory()

		trackersFile = dir.resolve("trackers.pfs")
		cameraAlignmentImage = dir.resolve("camera_alignment.png")
		reconstructionVideo = dir.resolve("reconstruction.mp4")
		skeletonOffsetsVideo = dir.resolve("skeleton_offsets.mp4")
	}

	fun webcamImage(timestamp: Duration): Path = webcamDir.resolve("webcam_${timestamp.inWholeMilliseconds.toString().padStart(6, '0')}.jpg")

	fun saveWebcamImage(timestamp: Duration, image: BufferedImage) {
		ImageIO.write(image, "jpg", webcamImage(timestamp).toFile())
	}

	fun humanPoseImage(timestamp: Duration): Path = humanPosesDir.resolve("pose_${timestamp.inWholeMilliseconds.toString().padStart(6, '0')}.jpg")

	fun saveHumanPoseImage(timestamp: Duration, image: BufferedImage) {
		ImageIO.write(image, "jpg", humanPoseImage(timestamp).toFile())
	}

	fun saveHandToControllerMatches(
		correspondences: List<Pair<Vector2D?, Vector2D>>,
		imageSize: Dimension,
	) {
		val image = BufferedImage(imageSize.width, imageSize.height, BufferedImage.TYPE_INT_RGB)

		val g = image.createGraphics()

		g.background = MATCH_BACKGROUND
		g.clearRect(0, 0, image.width, image.height)

		for ((controller, joint) in correspondences) {
			if (controller == null) {
				continue
			}

			drawLine(g, controller, joint, MATCH_MATCH_COLOR, MATCH_STROKE)

			drawLine(g, controller + Vector2D(-MATCH_MARKER_SIZE, -MATCH_MARKER_SIZE), controller + Vector2D(MATCH_MARKER_SIZE, MATCH_MARKER_SIZE), MATCH_CONTROLLER_COLOR, MATCH_STROKE)
			drawLine(g, controller + Vector2D(MATCH_MARKER_SIZE, -MATCH_MARKER_SIZE), controller + Vector2D(-MATCH_MARKER_SIZE, MATCH_MARKER_SIZE), MATCH_CONTROLLER_COLOR, MATCH_STROKE)

			drawCircle(g, joint, MATCH_MARKER_SIZE, MATCH_JOINT_COLOR, MATCH_STROKE)
		}

		for (i in 0 until correspondences.size - 1) {
			val pA = correspondences[i].second
			val pB = correspondences[i + 1].second
			drawLine(g, pA, pB, MATCH_PATH_COLOR, MATCH_STROKE)
		}

		g.dispose()

		ImageIO.write(image, "png", cameraAlignmentImage.toFile())
	}

	private fun drawAxis(g: Graphics2D, rotation: QuaternionD, imagePoint: Vector2D, camera: Camera, scale: Double) {
		val x = camera.project(rotation.sandwichUnitX() * scale, imagePoint, 1.0)
		val y = camera.project(rotation.sandwichUnitY() * scale, imagePoint, 1.0)
		val z = camera.project(rotation.sandwichUnitZ() * scale, imagePoint, 1.0)
		if (x != null && y != null && z != null) {
			drawLine(g, imagePoint, imagePoint + x, AXIS_X_COLOR, AXIS_STROKE)
			drawLine(g, imagePoint, imagePoint + y, AXIS_Y_COLOR, AXIS_STROKE)
			drawLine(g, imagePoint, imagePoint + z, AXIS_Z_COLOR, AXIS_STROKE)
		}
	}

	private fun drawBones(g: Graphics2D, humanPoseSnapshot: HumanPoseSnapshot) {
		for ((j1, j2) in BONES) {
			val joint1 = humanPoseSnapshot.joints[j1] ?: continue
			val joint2 = humanPoseSnapshot.joints[j2] ?: continue
			drawLine(g, joint1, joint2, BONE_COLOR, BONE_STROKE)
		}
	}

	private fun drawLine(g: Graphics2D, p1: Vector2D, p2: Vector2D, color: Color, stroke: Stroke) {
		g.color = color
		g.stroke = stroke
		g.drawLine(p1.x.toInt(), p1.y.toInt(), p2.x.toInt(), p2.y.toInt())
	}

	private fun drawCircle(g: Graphics2D, p: Vector2D, r: Double, color: Color, stroke: Stroke) {
		g.color = color
		g.stroke = stroke
		g.drawOval((p.x - r).toInt(), (p.y - r).toInt(), (2.0 * r).toInt(), (2.0 * r).toInt())
	}

	companion object {

		private const val VIDEO_CALIBRATION_FOLDER = "VideoCalibration"

		private val BONES = listOf(
			HumanJoint.LEFT_SHOULDER to HumanJoint.RIGHT_SHOULDER,
			HumanJoint.LEFT_SHOULDER to HumanJoint.LEFT_ELBOW,
			HumanJoint.LEFT_ELBOW to HumanJoint.LEFT_WRIST,
			HumanJoint.RIGHT_SHOULDER to HumanJoint.RIGHT_ELBOW,
			HumanJoint.RIGHT_ELBOW to HumanJoint.RIGHT_WRIST,
			HumanJoint.LEFT_HIP to HumanJoint.RIGHT_HIP,
			HumanJoint.LEFT_HIP to HumanJoint.LEFT_KNEE,
			HumanJoint.LEFT_KNEE to HumanJoint.LEFT_ANKLE,
			HumanJoint.RIGHT_HIP to HumanJoint.RIGHT_KNEE,
			HumanJoint.RIGHT_KNEE to HumanJoint.RIGHT_ANKLE,
			HumanJoint.LEFT_SHOULDER to HumanJoint.LEFT_HIP,
			HumanJoint.RIGHT_SHOULDER to HumanJoint.RIGHT_HIP,
		)

		private val TRACKER_TO_BONE = mapOf(
			TrackerPosition.LEFT_UPPER_ARM to (HumanJoint.LEFT_SHOULDER to HumanJoint.LEFT_ELBOW),
			TrackerPosition.RIGHT_UPPER_ARM to (HumanJoint.RIGHT_SHOULDER to HumanJoint.RIGHT_ELBOW),
			TrackerPosition.LEFT_UPPER_LEG to (HumanJoint.LEFT_HIP to HumanJoint.LEFT_KNEE),
			TrackerPosition.LEFT_LOWER_LEG to (HumanJoint.LEFT_KNEE to HumanJoint.LEFT_ANKLE),
			TrackerPosition.RIGHT_UPPER_LEG to (HumanJoint.RIGHT_HIP to HumanJoint.RIGHT_KNEE),
			TrackerPosition.RIGHT_LOWER_LEG to (HumanJoint.RIGHT_KNEE to HumanJoint.RIGHT_ANKLE),
		)

		private val UPPER_BODY_TRACKERS = listOf(
			TrackerPosition.UPPER_CHEST,
			TrackerPosition.CHEST,
			TrackerPosition.WAIST,
			TrackerPosition.HIP,
		)

		private val MATCH_BACKGROUND = Color.WHITE
		private val MATCH_STROKE = BasicStroke(2.0f)
		private val MATCH_PATH_COLOR = Color.GRAY
		private val MATCH_MATCH_COLOR = Color.GRAY
		private val MATCH_MARKER_SIZE = 5.0
		private val MATCH_JOINT_COLOR = Color.RED
		private val MATCH_CONTROLLER_COLOR = Color.BLUE

		private val BONE_COLOR = Color.GRAY
		private val BONE_STROKE = BasicStroke(4.0f)

		private val AXIS_STROKE = BasicStroke(6.0f)
		private val AXIS_X_COLOR = Color.RED
		private val AXIS_Y_COLOR = Color.GREEN
		private val AXIS_Z_COLOR = Color.BLUE
		private const val AXIS_SCALE = 0.05

		private val SKELETON_BONE_COLOR = Color.GREEN

		val DEFAULT_DIR: Path =
			OperatingSystem
				.resolveConfigDirectory(SLIMEVR_IDENTIFIER)!!
				.resolve(VIDEO_CALIBRATION_FOLDER)
	}
}
