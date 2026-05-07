package dev.slimevr.tracking.videocalibration

import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.human.HumanPoseSnapshot
import dev.slimevr.tracking.videocalibration.trackers.IMUTracker
import dev.slimevr.tracking.videocalibration.trackers.PositionalTracker
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshot
import kotlin.time.Duration
import kotlin.time.TimeSource

class Database {
	private val lock = Any()
	private val allHumanPoseSnapshots = mutableListOf<HumanPoseSnapshot>()
	private val recentHumanPoseSnapshots = mutableListOf<HumanPoseSnapshot>()
	private val allTrackersSnapshots = mutableListOf<TrackersSnapshot>()
	private val recentTrackersSnapshots = mutableListOf<TrackersSnapshot>()

	fun addHumanPoseSnapshot(snapshot: HumanPoseSnapshot) {
		synchronized(lock) {
			if (allHumanPoseSnapshots.isEmpty() || snapshot.timestamp > allHumanPoseSnapshots.last().timestamp) {
				allHumanPoseSnapshots.add(snapshot)
				recentHumanPoseSnapshots.add(snapshot)
			}
		}
	}

	fun addTrackersSnapshot(snapshot: TrackersSnapshot) {
		synchronized(lock) {
			if (allTrackersSnapshots.isEmpty() || snapshot.timestamp > allTrackersSnapshots.last().timestamp) {
				allTrackersSnapshots.add(snapshot)
				recentTrackersSnapshots.add(snapshot)
			}
		}
	}

	fun getAllTrackerSnapshots(): List<TrackersSnapshot> {
		synchronized(lock) {
			return allTrackersSnapshots.toList()
		}
	}

	fun getRecentTrackerSnapshots(n: Int): List<TrackersSnapshot> {
		synchronized(lock) {
			return recentTrackersSnapshots.takeLast(n)
		}
	}

	fun clear() {
		synchronized(lock) {
			allHumanPoseSnapshots.clear()
			allTrackersSnapshots.clear()
			recentHumanPoseSnapshots.clear()
			recentTrackersSnapshots.clear()
		}
	}

	fun clearRecent() {
		synchronized(lock) {
			recentHumanPoseSnapshots.clear()
			recentTrackersSnapshots.clear()
		}
	}

	fun newestHumanPoseSnapshot(): HumanPoseSnapshot? {
		synchronized(lock) {
			return allHumanPoseSnapshots.lastOrNull()
		}
	}

	fun matchAll(controllersToHumanPoseDelay: Duration, trackerToHumanPoseDelay: Map<TrackerPosition, Duration>) = match(allHumanPoseSnapshots, allTrackersSnapshots, controllersToHumanPoseDelay, trackerToHumanPoseDelay)

	fun matchAllControllers(controllersToHumanPoseDelay: Duration) = matchControllers(allHumanPoseSnapshots, allTrackersSnapshots, controllersToHumanPoseDelay)

	fun matchRecentControllers(controllersToHumanPoseDelay: Duration) = matchControllers(recentHumanPoseSnapshots, recentTrackersSnapshots, controllersToHumanPoseDelay)

	fun matchAllIMUTrackers(imuTrackersToHumanPoseDelay: Duration) = matchIMUTrackers(allHumanPoseSnapshots, allTrackersSnapshots, imuTrackersToHumanPoseDelay)

	fun matchRecentIMUTrackers(imuTrackersToHumanPoseDelay: Duration) = matchIMUTrackers(recentHumanPoseSnapshots, recentTrackersSnapshots, imuTrackersToHumanPoseDelay)

	data class Match(
		val humanPose: HumanPoseSnapshot,
		val trackersSnapshot: TrackersSnapshot,
	)

	data class ControllerMatch(
		val humanPose: HumanPoseSnapshot,
		val controllers: Map<TrackerPosition, PositionalTracker>,
	)

	data class IMUTrackerMatch(
		val humanPose: HumanPoseSnapshot,
		val imuTrackers: Map<TrackerPosition, IMUTracker>,
	)

	fun match(
		humanPoses: List<HumanPoseSnapshot>,
		trackersSnapshots: List<TrackersSnapshot>,
		controllersToHumanPoseDelay: Duration,
		trackerToHumanPoseDelay: Map<TrackerPosition, Duration>,
	): List<Match> {
		synchronized(lock) {
			if (humanPoses.isEmpty() || trackersSnapshots.isEmpty()) {
				return emptyList()
			}

			val result = mutableListOf<Match>()

			// Controllers use one shared index
			var controllerIndex = 0

			// IMUs use per-tracker indices
			val imuIndices = mutableMapOf<TrackerPosition, Int>()

			val requiredImus: Set<TrackerPosition> =
				trackersSnapshots.flatMap { it.imuTrackers.keys }.toSet()

			fun findBestIndex(
				startIndex: Int,
				targetTime: TimeSource.Monotonic.ValueTimeMark,
			): Int {
				var index = startIndex

				while (index < trackersSnapshots.lastIndex) {
					val current = trackersSnapshots[index]
					val next = trackersSnapshots[index + 1]

					val currentDiff = (current.timestamp - targetTime).absoluteValue
					val nextDiff = (next.timestamp - targetTime).absoluteValue

					if (nextDiff < currentDiff) {
						index++
					} else {
						break
					}
				}

				while (index > 0) {
					val current = trackersSnapshots[index]
					val prev = trackersSnapshots[index - 1]

					val currentDiff = (current.timestamp - targetTime).absoluteValue
					val prevDiff = (prev.timestamp - targetTime).absoluteValue

					if (prevDiff < currentDiff) {
						index--
					} else {
						break
					}
				}

				return index
			}

			for (humanPose in humanPoses) {
				val humanTime = humanPose.timestamp

				// ---- Controllers (shared delay) ----
				val controllerTargetTime = humanTime - controllersToHumanPoseDelay
				controllerIndex = findBestIndex(controllerIndex, controllerTargetTime)
				val controllerSnapshot = trackersSnapshots[controllerIndex]

				if (controllerSnapshot.controllers.isEmpty()) continue

				val controllers = controllerSnapshot.controllers

				// ---- IMUs (per-tracker delay) ----
				val imus = mutableMapOf<TrackerPosition, IMUTracker>()
				var missing = false

				for (imuPos in requiredImus) {
					val delay = trackerToHumanPoseDelay[imuPos] ?: controllersToHumanPoseDelay
					val targetTime = humanTime - delay

					val startIndex = imuIndices[imuPos] ?: 0
					val bestIndex = findBestIndex(startIndex, targetTime)
					imuIndices[imuPos] = bestIndex

					val imu = trackersSnapshots[bestIndex].imuTrackers[imuPos]

					if (imu == null) {
						missing = true
						break
					}

					imus[imuPos] = imu
				}

				if (missing) continue

				val fused = TrackersSnapshot(
					timestamp = humanTime,
					controllers = controllers,
					imuTrackers = imus,
				)

				result += Match(humanPose, fused)
			}

			return result
		}
	}

	// TODO: Review AI code
	private fun matchControllers(
		humanPoses: List<HumanPoseSnapshot>,
		trackersSnapshots: List<TrackersSnapshot>,
		controllersToHumanPoseDelay: Duration,
	): List<ControllerMatch> {
		synchronized(lock) {
			if (humanPoses.isEmpty() || trackersSnapshots.isEmpty()) {
				return emptyList()
			}

			val result = mutableListOf<ControllerMatch>()
			var index = 0

			for (humanPose in humanPoses) {
				val targetTime = humanPose.timestamp - controllersToHumanPoseDelay

				// Move index toward targetTime (monotonic forward)
				while (index < trackersSnapshots.lastIndex) {
					val current = trackersSnapshots[index]
					val next = trackersSnapshots[index + 1]

					val currentDiff = (current.timestamp - targetTime).absoluteValue
					val nextDiff = (next.timestamp - targetTime).absoluteValue

					if (nextDiff < currentDiff) {
						index++
					} else {
						break
					}
				}

				val best = trackersSnapshots[index]

				// Require all controllers to be present
				if (best.controllers.isEmpty()) continue

				result += ControllerMatch(
					humanPose = humanPose,
					controllers = best.controllers,
				)
			}

			return result
		}
	}

	private fun matchIMUTrackers(
		humanPoses: List<HumanPoseSnapshot>,
		trackersSnapshots: List<TrackersSnapshot>,
		imuTrackersToHumanPoseDelay: Duration,
	): List<IMUTrackerMatch> {
		synchronized(lock) {
			if (humanPoses.isEmpty() || trackersSnapshots.isEmpty()) {
				return emptyList()
			}

			val result = mutableListOf<IMUTrackerMatch>()
			var index = 0

			// Determine required IMUs (stable set)
			val requiredImus: Set<TrackerPosition> =
				trackersSnapshots.flatMap { it.imuTrackers.keys }.toSet()

			for (humanPose in humanPoses) {
				val targetTime = humanPose.timestamp - imuTrackersToHumanPoseDelay

				// Move index toward targetTime
				while (index < trackersSnapshots.lastIndex) {
					val current = trackersSnapshots[index]
					val next = trackersSnapshots[index + 1]

					val currentDiff = (current.timestamp - targetTime).absoluteValue
					val nextDiff = (next.timestamp - targetTime).absoluteValue

					if (nextDiff < currentDiff) {
						index++
					} else {
						break
					}
				}

				val best = trackersSnapshots[index]

				// Enforce completeness
				if (!best.imuTrackers.keys.containsAll(requiredImus)) continue

				result += IMUTrackerMatch(
					humanPose = humanPose,
					imuTrackers = best.imuTrackers,
				)
			}

			return result
		}
	}
}
