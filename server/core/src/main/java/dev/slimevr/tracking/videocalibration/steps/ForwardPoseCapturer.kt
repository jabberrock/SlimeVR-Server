package dev.slimevr.tracking.videocalibration.steps

import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.Database
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshot
import io.eiren.util.logging.Logger
import io.github.axisangles.ktmath.Matrix3D
import io.github.axisangles.ktmath.QuaternionD
import io.github.axisangles.ktmath.Vector3D
import io.github.axisangles.ktmath.toAngleAxisString
import org.apache.commons.math3.util.FastMath
import kotlin.math.*

class ForwardPoseCapturer {

	private val logger = Logger(this::class.simpleName)

	class Solution(
		// Reference yaw (to align the Z-axes of all the trackers, i.e. backwards)
		val reference: QuaternionD,
		// Snapshot of rotations of all the trackers when the reference yaw was captured
		val trackerRotations: List<TrackersSnapshot>,
	)

	private val maxAngleDeviation = FastMath.toRadians(5.0)
	private val maxAngleFromVerticalDeg = 20.0
	private val minStableSnapshots = 200

	fun capture(database: Database): Solution? {
		val snapshots = database.getRecentTrackerSnapshots(minStableSnapshots)
		if (snapshots.size < minStableSnapshots) {
			logger.debug("Not enough trackers snapshots: ${snapshots.size} < $minStableSnapshots")
			return null
		}

		val newestSnapshot = snapshots.last()
		val newestHead = newestSnapshot.controllers[TrackerPosition.HEAD] ?: return null

		// HEAD tracker must be level
		val angleFromLevelDeg =
			FastMath.toDegrees(
				acos(
					newestHead.trackerToOVRWorld.sandwichUnitY()
						.dot(Vector3D.POS_Y)
						.coerceIn(-1.0, 1.0),
				),
			)
		if (angleFromLevelDeg > maxAngleFromVerticalDeg) {
			logger.debug("Head is not level: $angleFromLevelDeg > $maxAngleFromVerticalDeg")
			return null
		}

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

		// TODO: Get average over duration instead

		val headZAxis = newestHead.trackerToOVRWorld.sandwichUnitZ()
		val zAxis = Vector3D(headZAxis.x, 0.0, headZAxis.z).unit()
		val yAxis = Vector3D.POS_Y
		val xAxis = yAxis.cross(zAxis)
		val reference = Matrix3D(xAxis, yAxis, zAxis).toQuaternionAssumingOrthonormal()

		logger.info("Found forward pose: ${reference.toAngleAxisString()}")

		return Solution(reference, snapshots.takeLast(NUM_TRACKERS_SNAPSHOTS_TO_KEEP))
	}

	companion object {
		private const val NUM_TRACKERS_SNAPSHOTS_TO_KEEP = 50
	}
}
