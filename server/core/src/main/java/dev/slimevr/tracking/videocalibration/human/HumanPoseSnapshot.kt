package dev.slimevr.tracking.videocalibration.human

import dev.slimevr.tracking.videocalibration.vision.Camera
import io.github.axisangles.ktmath.Vector2D
import kotlin.time.TimeSource

class HumanPoseSnapshot(
	val timestamp: TimeSource.Monotonic.ValueTimeMark,
	val joints: Map<HumanJoint, Vector2D>,
	val camera: Camera,
)
