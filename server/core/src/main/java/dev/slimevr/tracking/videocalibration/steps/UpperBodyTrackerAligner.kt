package dev.slimevr.tracking.videocalibration.steps

import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.TrackerResetOverride
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshot
import dev.slimevr.tracking.videocalibration.util.numericalJacobian
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.QuaternionD
import org.apache.commons.math3.analysis.MultivariateVectorFunction
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer
import org.apache.commons.math3.util.FastMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

class UpperBodyTrackerAligner {

	fun solve(
		trackerPosition: TrackerPosition,
		forwardPose: QuaternionD,
		forwardPoseTrackers: List<TrackersSnapshot>,
		leaningForwardPoseTrackers: List<TrackersSnapshot>,
	): TrackerResetOverride? {
		val n = forwardPoseTrackers.size + leaningForwardPoseTrackers.size * 2

		val costFn = MultivariateVectorFunction { p ->
			val reset = buildTrackerReset(p)

			val residual = DoubleArray(n) { 0.0 }

			var i = 0
			for (snapshot in forwardPoseTrackers) {
				val tracker = snapshot.imuTrackers[trackerPosition]
				if (tracker != null) {
					val trackerBone = reset.toBoneRotation(tracker.trackerToArbitraryWorld)
					residual[i++] = trackerBone.angleToR(forwardPose)
				}
			}
			for (snapshot in leaningForwardPoseTrackers) {
				val tracker = snapshot.imuTrackers[trackerPosition]
				if (tracker != null) {
					val trackerBone = reset.toBoneRotation(tracker.trackerToArbitraryWorld)
					residual[i++] = trackerBone.sandwichUnitX().angleTo(forwardPose.sandwichUnitX())

					// y-axis should be facing forward, instead of backwards
					// Rotate forwardPose forward instead of using bentOverPose because the standing forwardPose is usually more balanced
					val bentOverYAxis = (forwardPose * QuaternionD.rotationAroundXAxis(-PI / 4.0)).sandwichUnitY()
					val bentOverAngle = trackerBone.sandwichUnitY().angleTo(bentOverYAxis)
					residual[i++] = max(bentOverAngle - FastMath.toRadians(30.0), 0.0)
				}
			}

			residual
		}

		val model = numericalJacobian(costFn)

		var bestRMS = Double.POSITIVE_INFINITY
		var bestInitialParams: DoubleArray? = null
		for (initialParams in startingParams()) {
			val rms = calcError(initialParams, trackerPosition, forwardPose, forwardPoseTrackers, leaningForwardPoseTrackers)
			if (rms < bestRMS) {
				bestRMS = rms
				bestInitialParams = initialParams
			}
		}

		if (bestInitialParams == null) {
			LogManager.warning("Failed to find best initial params")
			return null
		}

		LogManager.info("Best initial params: ${bestInitialParams.toList()} rms=$bestRMS")

		val problem = LeastSquaresBuilder()
			.start(bestInitialParams)
			.model(model)
			.target(DoubleArray(n) { 0.0 })
			.maxEvaluations(10000)
			.maxIterations(10000)
			.checker { iter, prev, current ->
// 				LogManager.info("$iter ${prev.rms} ${current.rms}")
				abs(current.rms - prev.rms) < 1e-7
			}
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

		LogManager.info("Found tracker resets for $trackerPosition: $trackerReset")

		return trackerReset
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

	private fun calcError(
		params: DoubleArray,
		trackerPosition: TrackerPosition,
		forwardPose: QuaternionD,
		forwardPoseTrackers: List<TrackersSnapshot>,
		leaningForwardPoseTrackers: List<TrackersSnapshot>,
	): Double {
		val reset = buildTrackerReset(params)

		var rms = 0.0
		for (snapshot in forwardPoseTrackers) {
			val tracker = snapshot.imuTrackers[trackerPosition]
			if (tracker != null) {
				val trackerBone = reset.toBoneRotation(tracker.trackerToArbitraryWorld)
				val error = trackerBone.angleToR(forwardPose)
				rms += error * error
			}
		}
		for (snapshot in leaningForwardPoseTrackers) {
			val tracker = snapshot.imuTrackers[trackerPosition]
			if (tracker != null) {
				val trackerBone = reset.toBoneRotation(tracker.trackerToArbitraryWorld)
				val error1 = trackerBone.sandwichUnitX().angleTo(forwardPose.sandwichUnitX())

				// y-axis should be facing forward, instead of backwards
				// Rotate forwardPose forward instead of using bentOverPose because the standing forwardPose is usually more balanced
				val bentOverYAxis = (forwardPose * QuaternionD.rotationAroundXAxis(-PI / 4.0)).sandwichUnitY()
				val bentOverAngle = trackerBone.sandwichUnitY().angleTo(bentOverYAxis)
				val error2 = max(bentOverAngle - FastMath.toRadians(30.0), 0.0)

				rms += error1 * error1 + error2 * error2
			}
		}

		return rms
	}
}
