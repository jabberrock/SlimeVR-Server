package dev.slimevr.tracking.videocalibration.trackers

import io.github.axisangles.ktmath.QuaternionD
import io.github.axisangles.ktmath.Vector3D

data class PositionalTracker(
	val trackerToOVRWorld: QuaternionD,
	val trackerOriginInOVRWorld: Vector3D,
)
