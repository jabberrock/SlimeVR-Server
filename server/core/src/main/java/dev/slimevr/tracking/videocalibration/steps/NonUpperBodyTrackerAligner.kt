package dev.slimevr.tracking.videocalibration.steps

import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.Database
import dev.slimevr.tracking.videocalibration.TrackerResetOverride
import dev.slimevr.tracking.videocalibration.human.HumanJoint
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshot
import dev.slimevr.tracking.videocalibration.util.numericalJacobian
import dev.slimevr.tracking.videocalibration.vision.Camera
import io.eiren.util.logging.LogManager
import io.eiren.util.logging.Logger
import io.github.axisangles.ktmath.QuaternionD
import io.github.axisangles.ktmath.Vector2D
import io.github.axisangles.ktmath.Vector3D
import org.apache.commons.math3.analysis.MultivariateVectorFunction
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer
import org.apache.commons.math3.util.FastMath
import kotlin.math.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class NonUpperBodyTrackerAligner {

	private val logger = Logger(this::class.simpleName)

	data class Solution(
		val trackerPosition: TrackerPosition,
		val trackerReset: TrackerResetOverride,
		val cameraDelay: Duration,
	)

	val trackerPositionToJoints = mapOf(
		TrackerPosition.LEFT_UPPER_LEG to Pair(HumanJoint.LEFT_HIP, HumanJoint.LEFT_KNEE),
		TrackerPosition.LEFT_LOWER_LEG to Pair(HumanJoint.LEFT_KNEE, HumanJoint.LEFT_ANKLE),
		TrackerPosition.RIGHT_UPPER_LEG to Pair(HumanJoint.RIGHT_HIP, HumanJoint.RIGHT_KNEE),
		TrackerPosition.RIGHT_LOWER_LEG to Pair(HumanJoint.RIGHT_KNEE, HumanJoint.RIGHT_ANKLE),
		TrackerPosition.LEFT_UPPER_ARM to Pair(HumanJoint.LEFT_SHOULDER, HumanJoint.LEFT_ELBOW),
		TrackerPosition.RIGHT_UPPER_ARM to Pair(HumanJoint.RIGHT_SHOULDER, HumanJoint.RIGHT_ELBOW),
		TrackerPosition.LEFT_LOWER_ARM to Pair(HumanJoint.LEFT_ELBOW, HumanJoint.LEFT_WRIST),
		TrackerPosition.RIGHT_LOWER_ARM to Pair(HumanJoint.RIGHT_ELBOW, HumanJoint.RIGHT_WRIST),
// 		TrackerPosition.LEFT_FOOT to Pair(HumanJoint.LEFT_ANKLE, HumanJoint.LEFT_BIG_TOE),
// 		TrackerPosition.RIGHT_FOOT to Pair(HumanJoint.RIGHT_ANKLE, HumanJoint.RIGHT_BIG_TOE),
	)

	private val minMatches = 150

	fun solve(
		trackerPosition: TrackerPosition,
		camera: Camera,
		forwardPose: QuaternionD,
		forwardPoseTrackers: List<TrackersSnapshot>,
		database: Database,
		estimatedTrackerToHumanPoseDelay: Duration,
	): Solution? {
		val frames = database.matchRecentIMUTrackers(estimatedTrackerToHumanPoseDelay)
		if (frames.isEmpty()) {
			return null
		}

		val joints = trackerPositionToJoints[trackerPosition]
		if (joints == null) {
			return null
		}

		val matches = buildMatches(trackerPosition, frames, joints.first, joints.second)

		val filteredMatches = filterMatches(matches)
		if (filteredMatches.size < minMatches) {
			return null
		}

		if (!enoughRotation(filteredMatches)) {
			return null
		}

		LogManager.info("Trying to solve $trackerPosition...")

		var bestRMS = Double.POSITIVE_INFINITY
		var bestTotalDelay: Duration? = null
		var bestTrackerReset: TrackerResetOverride? = null

		for (extraDelay in -100..100 step 10) {
			val totalDelay = estimatedTrackerToHumanPoseDelay + extraDelay.milliseconds
			val shiftedFrames = database.matchRecentIMUTrackers(totalDelay)
			val shiftedMatches = buildMatches(trackerPosition, shiftedFrames, joints.first, joints.second)
			val filteredShiftedMatches = filterMatches(shiftedMatches)
			if (filteredShiftedMatches.size < minMatches) {
				return null
			}

			var bestInitialRMS = Double.POSITIVE_INFINITY
			var bestInitialParams: DoubleArray? = null
			for (initialParams in startingParams()) {
				val rms = calcError(
					initialParams,
					trackerPosition,
					filteredShiftedMatches,
					camera,
					forwardPose,
					forwardPoseTrackers,
				)
				if (rms < bestInitialRMS) {
					bestInitialRMS = rms
					bestInitialParams = initialParams
				}
			}

			if (bestInitialParams == null) {
				LogManager.warning("Failed to find best initial params")
				return null
			}

			val result = solveLM(
				trackerPosition,
				filteredShiftedMatches,
				camera,
				forwardPose,
				forwardPoseTrackers,
				bestInitialParams,
			)
			if (result == null) {
				LogManager.warning("Failed to optimize tracker")
				return null
			}

			val (trackerReset, rms) = result
			if (rms < bestRMS) {
				bestRMS = rms
				bestTotalDelay = totalDelay
				bestTrackerReset = trackerReset
			}
		}

		if (bestTotalDelay == null || bestTrackerReset == null) {
			return null
		}

		LogManager.info("Found tracker resets for $trackerPosition: rms=$bestRMS $bestTrackerReset delay=$bestTotalDelay")

		val cleanMatches = removeWorstMatches(trackerPosition, filteredMatches, camera, bestTrackerReset)
		val cleanResult = solveLM(trackerPosition, cleanMatches, camera, forwardPose, forwardPoseTrackers, makeParams(bestTrackerReset))
		if (cleanResult == null) {
			logger.warning("Failed to improve tracker resets")
			return null
		}

		LogManager.info("Improved tracker resets for $trackerPosition: rms=${cleanResult.second} ${cleanResult.first} delay=$bestTotalDelay")

		return Solution(trackerPosition, cleanResult.first, bestTotalDelay)
	}

	private fun removeWorstMatches(
		trackerPosition: TrackerPosition,
		matches: List<Match>,
		camera: Camera,
		trackerReset: TrackerResetOverride,
	) = matches.sortedBy { calcError(trackerPosition, it, camera, trackerReset) }.take(matches.size * 8 / 10)

	class Match(
		val trackerRotation: QuaternionD,
		val lowerJoint: Vector2D,
		val upperJoint: Vector2D,
		val boneDir: Vector2D,
	)

	private fun buildMatches(
		trackerPosition: TrackerPosition,
		frames: List<Database.IMUTrackerMatch>,
		upperJoint: HumanJoint,
		lowerJoint: HumanJoint,
	): List<Match> {
		val matches = mutableListOf<Match>()
		for (frame in frames) {
			val tracker = frame.imuTrackers[trackerPosition] ?: continue

			val r = tracker.trackerToArbitraryWorld

			// Tracker Y-axis points from lower joint to upper joint
			val upperJoint = frame.humanPose.joints[upperJoint] ?: continue
			val lowerJoint = frame.humanPose.joints[lowerJoint] ?: continue
			val boneDir = (upperJoint - lowerJoint).unit()

			matches += Match(r, lowerJoint, upperJoint, boneDir)
		}

		return matches
	}

	private val minBoneLength = 30.0
	private val minAngleDeviation = FastMath.toRadians(5.0)

	private fun filterMatches(matches: List<Match>): List<Match> {
		val filtered = mutableListOf<Match>()
		for (match in matches.asReversed()) {
			if ((match.upperJoint - match.lowerJoint).len() < minBoneLength) {
				continue
			}

			val lastMatch = filtered.lastOrNull()
			if (
				lastMatch == null ||
				lastMatch.trackerRotation.angleToR(match.trackerRotation) >= minAngleDeviation
			) {
				filtered += match
			}
		}

		return filtered
	}

	private val minMaxRotation = FastMath.toRadians(60.0)

	private fun enoughRotation(matches: List<Match>): Boolean {
		for (i in matches) {
			for (j in matches) {
				// TODO: Should look at non-yaw rotation
				if (i.trackerRotation.angleToR(j.trackerRotation) >= minMaxRotation) {
					return true
				}
			}
		}
		return false
	}

	private fun solveLM(
		trackerPosition: TrackerPosition,
		matches: List<Match>,
		camera: Camera,
		forwardPose: QuaternionD,
		forwardPoseTrackers: List<TrackersSnapshot>,
		initialParams: DoubleArray,
	): Pair<TrackerResetOverride, Double>? {
		val n = forwardPoseTrackers.size + matches.size

		val costFn = MultivariateVectorFunction { p ->
			val reset = buildTrackerReset(p)

			val residual = DoubleArray(n) { 0.0 }

			var i = 0

			// Ensure that the bone is aligned to the reference direction
			for (frame in forwardPoseTrackers) {
				val tracker = frame.imuTrackers[trackerPosition]
				if (tracker != null) {
					val trackerBone = reset.toBoneRotation(tracker.trackerToArbitraryWorld)
// 					residual[i++] = trackerBone.angleToR(forwardPose.reference)
					val z = trackerBone.sandwichUnitZ()
					val z2 = Vector3D(z.x, 0.0, z.z).unit()
					residual[i++] = z2.angleTo(forwardPose.sandwichUnitZ())
				}
			}

			// Ensure that the after-reset tracker Y-axis is aligned to the bone
			for (match in matches) {
				val trackerBone = reset.toBoneRotation(match.trackerRotation)
				val projectedBoneDir =
					camera.project(
						if (trackerPosition != TrackerPosition.HIP) trackerBone.sandwichUnitY() else trackerBone.sandwichUnitX(),
						(match.lowerJoint + match.upperJoint) * 0.5,
						1.0,
					)
				if (projectedBoneDir != null) {
					residual[i++] = projectedBoneDir.angleTo(match.boneDir)
				} else {
					residual[i++] = 1.0e6
				}
			}

			residual
		}

		val model = numericalJacobian(costFn)

		val problem = LeastSquaresBuilder()
			.start(initialParams)
			.model(model)
			.target(DoubleArray(n) { 0.0 })
			.maxEvaluations(10000)
			.maxIterations(10000)
// 			.checker { iter, prev, current ->
// // 				LogManager.info("$iter ${prev.rms} ${current.rms}")
// 				abs(current.rms - prev.rms) < 1e-7
// 			}
			.build()

		val optimizer = LevenbergMarquardtOptimizer()

		val result: LeastSquaresOptimizer.Optimum
		try {
			result = optimizer.optimize(problem)
		} catch (e: Exception) {
			LogManager.warning("Failed to optimize tracker: $e")
			return null
		}

		val trackerReset = buildTrackerReset(result.point.toArray())

		return Pair(trackerReset, result.rms)
	}

	private fun buildTrackerReset(p: DoubleArray): TrackerResetOverride {
		val override = TrackerResetOverride(p[0], p[1], p[2], p[3])
		return override
	}

	private fun startingParams() = iterator {
		val count = 4
		for (gy in 0 until count) {
			val globalYaw = 2.0 * PI / count * gy
			for (ly in 0 until count) {
				val localYaw = 2.0 * PI / count * ly
				for (lr in 0 until count) {
					val localRoll = 2.0 * PI / count * lr
					for (lp in 0 until count) {
						val localPitch = 2.0 * PI / count * lp
						yield(doubleArrayOf(globalYaw, localYaw, localRoll, localPitch))
					}
				}
			}
		}
	}

	private fun makeParams(trackerReset: TrackerResetOverride) = doubleArrayOf(
		trackerReset.globalYaw,
		trackerReset.localYaw,
		trackerReset.localRoll,
		trackerReset.localPitch,
	)

	fun calcError(
		params: DoubleArray,
		trackerPosition: TrackerPosition,
		matches: List<Match>,
		camera: Camera,
		forwardPose: QuaternionD,
		forwardPoseTrackers: List<TrackersSnapshot>,
	): Double {
		val reset = buildTrackerReset(params)

		var rms = 0.0

		// Ensure that the bone is aligned to the reference direction
		for (frame in forwardPoseTrackers) {
			val tracker = frame.imuTrackers[trackerPosition]
			if (tracker != null) {
				val trackerBone = reset.toBoneRotation(tracker.trackerToArbitraryWorld)
				val error = trackerBone.angleToR(forwardPose)
				rms += error * error
			}
		}

		// Ensure that the after-reset tracker Y-axis is aligned to the bone
		for (match in matches) {
			rms += calcError(trackerPosition, match, camera, reset)
		}

		return rms
	}

	fun calcError(
		trackerPosition: TrackerPosition,
		match: Match,
		camera: Camera,
		trackerReset: TrackerResetOverride,
	): Double {
		val trackerBone = trackerReset.toBoneRotation(match.trackerRotation)
		val projectedBoneDir =
			camera.project(
				if (trackerPosition != TrackerPosition.HIP) trackerBone.sandwichUnitY() else trackerBone.sandwichUnitX(),
				(match.lowerJoint + match.upperJoint) * 0.5,
				1.0,
			)
		if (projectedBoneDir != null) {
			val error = projectedBoneDir.angleTo(match.boneDir)
			return error * error
		} else {
			val error = 1.0e6
			return error * error
		}
	}
}
