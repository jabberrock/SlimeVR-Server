package dev.slimevr.tracking.videocalibration.trackers

import io.github.axisangles.ktmath.QuaternionD

data class IMUTracker(
	val trackerToArbitraryWorld: QuaternionD,
)
