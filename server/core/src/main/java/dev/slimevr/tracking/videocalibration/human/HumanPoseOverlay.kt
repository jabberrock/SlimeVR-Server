package dev.slimevr.tracking.videocalibration.human

import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshot
import dev.slimevr.tracking.videocalibration.vision.Camera
import io.github.axisangles.ktmath.Vector2D
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage

object HumanPoseOverlay {

	fun draw(image: BufferedImage, joints: Map<HumanJoint, Vector2D>) {
		val g = image.createGraphics()

		g.color = BONE_COLOR
		g.stroke = BONE_STROKE
		for ((a, b) in BONES) {
			val aj = joints[a] ?: continue
			val bj = joints[b] ?: continue
			g.drawLine(aj.x.toInt(), aj.y.toInt(), bj.x.toInt(), bj.y.toInt())
		}

		val ls = joints[HumanJoint.LEFT_SHOULDER]
		val rs = joints[HumanJoint.RIGHT_SHOULDER]
		val lh = joints[HumanJoint.LEFT_HIP]
		val rh = joints[HumanJoint.RIGHT_HIP]
		if (ls != null && rs != null && lh != null && rh != null) {
			val ms = (ls + rs) * 0.5
			val mh = (lh + rh) * 0.5
			g.drawLine(ms.x.toInt(), ms.y.toInt(), mh.x.toInt(), mh.y.toInt())
		}

		g.color = JOINT_COLOR
		for ((a, aj) in joints) {
			g.fillOval(aj.x.toInt() - JOINT_RADIUS, aj.y.toInt() - JOINT_RADIUS, JOINT_RADIUS * 2 + 1, JOINT_RADIUS * 2 + 1)
		}

		g.dispose()
	}

	fun drawControllers(image: BufferedImage, camera: Camera, snapshot: TrackersSnapshot) {
		val g = image.createGraphics()

		g.color = JOINT_COLOR
		g.stroke = BONE_STROKE

		for (trackerPosition in listOf(TrackerPosition.HEAD, TrackerPosition.LEFT_HAND, TrackerPosition.RIGHT_HAND)) {
			snapshot.controllers[trackerPosition]?.let { hand ->
				camera.project(hand.trackerOriginInOVRWorld)?.let { p ->
					g.drawOval(
						p.x.toInt() - CONTROLLER_RADIUS,
						p.y.toInt() - CONTROLLER_RADIUS,
						CONTROLLER_RADIUS * 2 + 1,
						CONTROLLER_RADIUS * 2 + 1,
					)
				}
			}
		}

		g.dispose()
	}

	private val BONES = listOf(
		HumanJoint.LEFT_SHOULDER to HumanJoint.RIGHT_SHOULDER,
		HumanJoint.LEFT_SHOULDER to HumanJoint.LEFT_HIP,
		HumanJoint.RIGHT_SHOULDER to HumanJoint.RIGHT_HIP,
		HumanJoint.LEFT_HIP to HumanJoint.RIGHT_HIP,
		HumanJoint.LEFT_HIP to HumanJoint.LEFT_KNEE,
		HumanJoint.LEFT_KNEE to HumanJoint.LEFT_ANKLE,
		HumanJoint.RIGHT_HIP to HumanJoint.RIGHT_KNEE,
		HumanJoint.RIGHT_KNEE to HumanJoint.RIGHT_ANKLE,
		HumanJoint.LEFT_SHOULDER to HumanJoint.LEFT_ELBOW,
		HumanJoint.LEFT_ELBOW to HumanJoint.LEFT_WRIST,
		HumanJoint.RIGHT_SHOULDER to HumanJoint.RIGHT_ELBOW,
		HumanJoint.RIGHT_ELBOW to HumanJoint.RIGHT_WRIST,
	)

	private val JOINT_COLOR = Color.GREEN
	private const val JOINT_RADIUS = 5

	private val BONE_COLOR = Color.GRAY
	private val BONE_STROKE = BasicStroke(7.0f)

	private val CONTROLLER_RADIUS = 10
}
