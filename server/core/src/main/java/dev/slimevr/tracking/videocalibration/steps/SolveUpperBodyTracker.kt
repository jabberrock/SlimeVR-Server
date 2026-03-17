package dev.slimevr.tracking.videocalibration.steps

import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.data.TrackerResetOverride
import dev.slimevr.tracking.videocalibration.util.numericalJacobian
import dev.slimevr.tracking.videocalibration.util.toAngleAxisString
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.QuaternionD
import org.apache.commons.math3.analysis.MultivariateVectorFunction
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer
import org.apache.commons.math3.util.FastMath
import kotlin.math.PI
import kotlin.math.max
import kotlin.random.Random

class SolveUpperBodyTracker {

	fun solve(
		trackerPosition: TrackerPosition,
		forwardPose: CaptureForwardPose.Solution,
		bentOverPose: CaptureBentOverPose.Solution,
	): TrackerResetOverride? {
		val n = forwardPose.trackerRotations.size + bentOverPose.trackerRotations.size * 2

		val costFn = MultivariateVectorFunction { p ->
			val reset = buildTrackerReset(p)

			val residual = DoubleArray(n) { 0.0 }

			var i = 0
			for (frame in forwardPose.trackerRotations) {
				val trackerRotation = frame[trackerPosition]
				if (trackerRotation != null) {
					val trackerBone = reset.toBoneRotation(trackerRotation)
					residual[i++] = trackerBone.angleToR(forwardPose.reference)
				}
			}
			for (frame in bentOverPose.trackerRotations) {
				val trackerRotation = frame[trackerPosition]
				if (trackerRotation != null) {
					val trackerBone = reset.toBoneRotation(trackerRotation)
					residual[i++] = trackerBone.sandwichUnitX().angleTo(bentOverPose.reference.sandwichUnitX())

					// y-axis should be facing forward, instead of backwards
					val bentOverYAxis = (forwardPose.reference * QuaternionD.rotationAroundXAxis(-PI / 4.0)).sandwichUnitY()
					val bentOverAngle = trackerBone.sandwichUnitY().angleTo(bentOverYAxis)
					residual[i++] = max(bentOverAngle - FastMath.toRadians(30.0), 0.0)
				}
			}

			residual
		}

		val model = numericalJacobian(costFn)

		var bestRMS = Double.POSITIVE_INFINITY
		var bestTrackerReset: TrackerResetOverride? = null
		val random = Random(System.currentTimeMillis())
		for (i in 0..5) {
			val randomYaw1 = random.nextDouble(-PI, PI)
			val randomYaw2 = random.nextDouble(-PI, PI)

			val initial = doubleArrayOf(
				randomYaw1, // pre-yaw
				1.0,
				0.0,
				randomYaw2,
				0.0, // post-rotation
			)

			val problem = LeastSquaresBuilder()
				.start(initial)
				.model(model)
				.target(DoubleArray(n) { 0.0 })
				.maxEvaluations(10000)
				.maxIterations(10000)
				.build()

			val optimizer = LevenbergMarquardtOptimizer()

			val result: LeastSquaresOptimizer.Optimum
			try {
				result = optimizer.optimize(problem)
			} catch (e: Exception) {
				continue
			}

			val trackerReset = buildTrackerReset(result.point.toArray())
			print("Found tracker reset for $trackerPosition: $trackerReset")

			val rms = result.rms
			if (rms < bestRMS) {
				bestRMS = rms
				bestTrackerReset = buildTrackerReset(result.point.toArray())
			}
		}

		if (bestTrackerReset == null) {
			return null
		}

		LogManager.info("Found tracker resets for $trackerPosition: $bestTrackerReset")

		return bestTrackerReset
	}

	private fun buildTrackerReset(p: DoubleArray): TrackerResetOverride = TrackerResetOverride(p[0], QuaternionD(p[1], p[2], p[3], p[4]).unit())
}
