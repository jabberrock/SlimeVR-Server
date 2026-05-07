package dev.slimevr.tracking.videocalibration.steps

import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.Database
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshot
import io.eiren.util.logging.LogManager
import io.eiren.util.logging.Logger
import io.github.axisangles.ktmath.toAngleAxisString
import org.apache.commons.math3.util.FastMath
import kotlin.time.Duration.Companion.seconds

class LeaningForwardPoseCapturer {

	private val logger = Logger(this::class.simpleName)

	class Solution(
// 		val reference: QuaternionD,
		// Snapshot of rotations of all the trackers when the bent over pose was captured
		val trackerRotations: List<TrackersSnapshot>,
	)

	private val minDuration = 1.seconds
	private val maxAngleDeviation = FastMath.toRadians(5.0)
	private val minStableSnapshots = 200

	// TODO: Same as SolveUpperBodyTrackerReset
	val trackerPositions = setOf(
		TrackerPosition.UPPER_CHEST,
		TrackerPosition.CHEST,
		TrackerPosition.WAIST,
		TrackerPosition.HIP,
	)

	val minBentOverAngle = FastMath.toRadians(30.0)

	fun capture(
		database: Database,
		trackersSnapshotsAtForwardPose: List<TrackersSnapshot>,
	): Solution? {
		val snapshots = database.getRecentTrackerSnapshots(minStableSnapshots)
		if (snapshots.size < minStableSnapshots) {
			logger.debug("Not enough trackers snapshots: ${snapshots.size} < $minStableSnapshots")
			return null
		}

		val newestSnapshot = snapshots.last()
		val newestHead = newestSnapshot.controllers[TrackerPosition.HEAD] ?: return null

		var numStableSnapshots = 0
		for (snapshot in snapshots.reversed()) {
			val snapshotHead = snapshot.controllers[TrackerPosition.HEAD] ?: continue
			if (newestHead.trackerToOVRWorld.angleToR(snapshotHead.trackerToOVRWorld) > maxAngleDeviation) {
				break
			}
			++numStableSnapshots
		}

		if (numStableSnapshots < minStableSnapshots) {
			logger.debug("Not enough stable snapshots: $numStableSnapshots < $minStableSnapshots")
			return null
		}

		for (trackerPosition in trackerPositions) {
			val bentOverTrackerRotation = newestSnapshot.imuTrackers[trackerPosition]
			val forwardPoseTrackerRotation = trackersSnapshotsAtForwardPose.last().imuTrackers[trackerPosition]
			if (bentOverTrackerRotation != null && forwardPoseTrackerRotation != null) {
				// TODO: Required tracker must have data
				if (bentOverTrackerRotation.trackerToArbitraryWorld.angleToR(
						forwardPoseTrackerRotation.trackerToArbitraryWorld,
					) < minBentOverAngle
				) {
					logger.debug("Skipping because not bent over enough")
					return null
				}
			}
		}

		// TODO: Get average over duration instead

		LogManager.info("Found bent-over pose: ${newestHead.trackerToOVRWorld.toAngleAxisString()}")

		return Solution(snapshots.takeLast(NUM_TRACKERS_SNAPSHOTS_TO_KEEP))
	}

	companion object {
		private const val NUM_TRACKERS_SNAPSHOTS_TO_KEEP = 50
	}
}
