package dev.slimevr.tracking.videocalibration.trackers

import dev.slimevr.tracking.trackers.TrackerPosition
import kotlin.time.TimeSource

class TrackersSnapshot(
	val timestamp: TimeSource.Monotonic.ValueTimeMark,
	val controllers: Map<TrackerPosition, PositionalTracker>,
	val imuTrackers: Map<TrackerPosition, IMUTracker>,
)
