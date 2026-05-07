package dev.slimevr.tracking.videocalibration.human

import io.github.axisangles.ktmath.Vector2D
import java.awt.image.BufferedImage

interface HumanPoseEstimator {
	fun estimate(image: BufferedImage): Map<HumanJoint, Vector2D>
}
