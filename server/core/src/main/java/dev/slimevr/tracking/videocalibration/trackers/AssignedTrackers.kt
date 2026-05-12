package dev.slimevr.tracking.videocalibration.trackers

import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.TrackerStatus

/**
 * Builds a set of trackers that need to be calibrated.
 */
data class AssignedTrackers(
	// Positional trackers
	val head: Tracker,
	val leftHand: Tracker,
	val rightHand: Tracker,

	// IMU trackers to calibrate
	val upperChest: Tracker?,
	val chest: Tracker,
	val waist: Tracker?,
	val hip: Tracker?,

	val leftUpperLeg: Tracker,
	val leftLowerLeg: Tracker,
	val leftFoot: Tracker?,
	val rightUpperLeg: Tracker,
	val rightLowerLeg: Tracker,
	val rightFoot: Tracker?,

	val leftUpperArm: Tracker?,
	val leftLowerArm: Tracker?,
	val rightUpperArm: Tracker?,
	val rightLowerArm: Tracker?,
) {
	val controllers =
		listOf(
			head,
			leftHand,
			rightHand,
		)

	val upperBodyTrackers =
		listOfNotNull(
			upperChest,
			chest,
			waist,
			hip,
		)

	val nonUpperBodyTrackers =
		listOfNotNull(
			leftUpperLeg,
			leftLowerLeg,
			leftFoot,
			rightUpperLeg,
			rightLowerLeg,
			rightFoot,
			leftUpperArm,
			leftLowerArm,
			rightUpperArm,
			rightLowerArm,
		)

	val trackers = upperBodyTrackers + nonUpperBodyTrackers

	fun ensureTrackersAreGood() {
		ensureTrackerIsGood(TrackerPosition.HEAD, head)
		ensureTrackerIsGood(TrackerPosition.LEFT_HAND, leftHand)
		ensureTrackerIsGood(TrackerPosition.RIGHT_HAND, rightHand)
		ensureTrackerIsGood(TrackerPosition.UPPER_CHEST, upperChest)
		ensureTrackerIsGood(TrackerPosition.CHEST, chest)
		ensureTrackerIsGood(TrackerPosition.WAIST, waist)
		ensureTrackerIsGood(TrackerPosition.HIP, hip)
		ensureTrackerIsGood(TrackerPosition.LEFT_UPPER_LEG, leftUpperLeg)
		ensureTrackerIsGood(TrackerPosition.LEFT_LOWER_LEG, leftLowerLeg)
		ensureTrackerIsGood(TrackerPosition.LEFT_FOOT, leftFoot)
		ensureTrackerIsGood(TrackerPosition.RIGHT_UPPER_LEG, rightUpperLeg)
		ensureTrackerIsGood(TrackerPosition.RIGHT_LOWER_LEG, rightLowerLeg)
		ensureTrackerIsGood(TrackerPosition.RIGHT_FOOT, rightFoot)
		ensureTrackerIsGood(TrackerPosition.LEFT_UPPER_ARM, leftUpperArm)
		ensureTrackerIsGood(TrackerPosition.LEFT_LOWER_ARM, leftLowerArm)
		ensureTrackerIsGood(TrackerPosition.RIGHT_UPPER_ARM, rightUpperArm)
		ensureTrackerIsGood(TrackerPosition.RIGHT_LOWER_ARM, rightLowerArm)
	}

	companion object {

		fun fromAllTrackers(trackers: List<Tracker>) = AssignedTrackers(
			// Positional trackers
			head = findRequiredPositionalTracker(TrackerPosition.HEAD, trackers),
			leftHand = findRequiredPositionalTracker(TrackerPosition.LEFT_HAND, trackers),
			rightHand = findRequiredPositionalTracker(TrackerPosition.RIGHT_HAND, trackers),
			upperChest = findOptionalIMUTracker(TrackerPosition.UPPER_CHEST, trackers),
			// IMU trackers to calibrate
			chest = findRequiredIMUTracker(TrackerPosition.CHEST, trackers),
			waist = findOptionalIMUTracker(TrackerPosition.WAIST, trackers),
			hip = findOptionalIMUTracker(TrackerPosition.HIP, trackers),
			leftUpperLeg = findRequiredIMUTracker(TrackerPosition.LEFT_UPPER_LEG, trackers),
			leftLowerLeg = findRequiredIMUTracker(TrackerPosition.LEFT_LOWER_LEG, trackers),
			leftFoot = findOptionalIMUTracker(TrackerPosition.LEFT_FOOT, trackers),
			rightUpperLeg = findRequiredIMUTracker(TrackerPosition.RIGHT_UPPER_LEG, trackers),
			rightLowerLeg = findRequiredIMUTracker(TrackerPosition.RIGHT_LOWER_LEG, trackers),
			rightFoot = findOptionalIMUTracker(TrackerPosition.RIGHT_FOOT, trackers),
			leftUpperArm = findOptionalIMUTracker(TrackerPosition.LEFT_UPPER_ARM, trackers),
			leftLowerArm = findOptionalIMUTracker(TrackerPosition.LEFT_LOWER_ARM, trackers),
			rightUpperArm = findOptionalIMUTracker(TrackerPosition.RIGHT_UPPER_ARM, trackers),
			rightLowerArm = findOptionalIMUTracker(TrackerPosition.RIGHT_LOWER_ARM, trackers),
		)

		private fun findRequiredPositionalTracker(trackerPosition: TrackerPosition, allTrackers: List<Tracker>): Tracker {
			val tracker = findOptionalTracker(trackerPosition, allTrackers) ?: error("Missing $trackerPosition tracker")

			// REVIEW: I wish there was a isPositional() check like isIMU()
			if (!tracker.hasPosition) {
				error("$trackerPosition is not a positional tracker")
			}

			return tracker
		}

		private fun findRequiredIMUTracker(trackerPosition: TrackerPosition, allTrackers: List<Tracker>): Tracker {
			val tracker = findOptionalIMUTracker(trackerPosition, allTrackers) ?: error("Missing $trackerPosition tracker")
			return tracker
		}

		private fun findOptionalIMUTracker(trackerPosition: TrackerPosition, allTrackers: List<Tracker>): Tracker? {
			val tracker = findOptionalTracker(trackerPosition, allTrackers) ?: return null

			if (!tracker.isImu()) {
				error("$trackerPosition is not an IMU tracker")
			}

//			val ping = tracker.ping
//			if (ping == null || ping >= 30L) {
//				error("$trackerPosition has no ping, or ping >= 30ms")
//			}

			return tracker
		}

		private fun findOptionalTracker(trackerPosition: TrackerPosition, allTrackers: List<Tracker>): Tracker? {
			val trackers =
				allTrackers.filter {
					it.trackerPosition == trackerPosition &&
						!it.isInternal &&
						it.status == TrackerStatus.OK
				}

			if (trackers.size > 1) {
				error("More than one tracked assigned to $trackerPosition position")
			}

			return trackers.firstOrNull()
		}

		private fun ensureTrackerIsGood(trackerPosition: TrackerPosition, tracker: Tracker?) {
			if (tracker == null) {
				return
			}

			if (tracker.trackerPosition != trackerPosition) {
				error("Tracker at $trackerPosition changed positions to ${tracker.trackerPosition}")
			}

			if (tracker.status != TrackerStatus.OK) {
				error("Tracker at $trackerPosition has non-OK status")
			}
		}
	}
}
