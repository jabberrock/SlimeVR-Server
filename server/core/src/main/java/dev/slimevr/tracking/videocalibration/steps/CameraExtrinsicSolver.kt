package dev.slimevr.tracking.videocalibration.steps

import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.Database
import dev.slimevr.tracking.videocalibration.human.HumanJoint
import dev.slimevr.tracking.videocalibration.trackers.PositionalTracker
import dev.slimevr.tracking.videocalibration.util.DebugOutput
import dev.slimevr.tracking.videocalibration.util.numericalJacobian
import dev.slimevr.tracking.videocalibration.vision.Camera
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

class CameraExtrinsicSolver(
	private val debugOutput: DebugOutput,
) {
	class Solution(
		val camera: Camera,
		val controllerDelay: Duration,
	)

	class NotEnoughSamplesException : Exception("Not enough samples")
	class NoSolutionException : Exception("No solution found")

	private val logger = Logger(this::class.simpleName)

	fun solve(database: Database): Solution {
		val newestHumanPose = database.newestHumanPoseSnapshot() ?: throw NotEnoughSamplesException()

		val zeroDelayMatches = matchRightHandPath(database, Duration.ZERO)
		if (zeroDelayMatches.size < MIN_MATCHES) {
			throw NotEnoughSamplesException()
		}

		val centroid =
			zeroDelayMatches
				.map { it.tracker.trackerOriginInOVRWorld }
				.reduce { acc, v -> acc + v } /
				zeroDelayMatches.size.toDouble()

		logger.debug("Tracker centroid: $centroid")

		var bestSolution: CameraSolution? = null
		for (controllerDelayInMs in 0 until MAX_CONTROLLER_DELAY_MS step CONTROLLER_DELAY_STEP_MS) {
			val controllerDelay = controllerDelayInMs.milliseconds
			val matches = matchRightHandPath(database, controllerDelay)

			for (yawDeg in 0 until 360 step CAMERA_YAW_STEP_DEG) {
				val positionedCamera =
					positionCamera(
						newestHumanPose.camera,
						centroid,
						INITIAL_CAMERA_DISTANCE_M,
						FastMath.toRadians(yawDeg.toDouble()),
					)

				val solution = solveCamera(positionedCamera, controllerDelay, matches)
				if (solution == null) {
					logger.debug("Failed to optimize camera params: delay=$controllerDelay yawDeg=$yawDeg")
					continue
				}

				if (bestSolution == null || solution.rms < bestSolution.rms) {
					bestSolution = solution

					val projectedMatches =
						matches.map { match ->
							projectWrist(
								solution.camera,
								match.tracker,
								solution.wristInController,
							) to match.joint
						}

					debugOutput.saveHandToControllerMatches(
						projectedMatches,
						solution.camera.imageSize,
					)
				}
			}
		}

		if (bestSolution == null) {
			throw NoSolutionException()
		}

		logger.debug("Solved camera: ${bestSolution.camera}")
		logger.debug("  controllerDelay=${bestSolution.controllerDelay}")
		logger.debug("  wristInController=${bestSolution.wristInController} len=${bestSolution.wristInController.len()}")

		return Solution(bestSolution.camera, bestSolution.controllerDelay)
	}

	private data class Match(
		val tracker: PositionalTracker,
		val joint: Vector2D,
	)

	// TODO: If we want to support left hand, we will need to optimize separate
	//  leftWristInController params, or assume that leftWristInController is a
	//  reflection of rightWristInController, which might be wrong
	private fun matchRightHandPath(database: Database, controllerDelay: Duration): List<Match> {
		val frames = database.matchRecentControllers(controllerDelay)

		val matches = mutableListOf<Match>()
		for ((humanFrame, controllers) in frames.reversed()) {
			val tracker = controllers[TrackerPosition.RIGHT_HAND] ?: continue
			val joint = humanFrame.joints[HumanJoint.RIGHT_WRIST] ?: continue

			val lastMatch = matches.lastOrNull()
			if (lastMatch == null || (tracker.trackerOriginInOVRWorld - lastMatch.tracker.trackerOriginInOVRWorld).len() > MIN_DISTANCE_BETWEEN_MATCH_M) {
				matches += Match(tracker, joint)
			}
		}

		return matches
	}

	/**
	 * Positions and rotates (yaw-only) a camera so that it is at [distance]
	 * away looking at [lookAt].
	 */
	private fun positionCamera(
		camera: Camera,
		lookAt: Vector3D,
		distance: Double,
		yawRad: Double,
	): Camera {
		val cameraZ = camera.extrinsic.cameraToWorld.sandwichUnitZ()
		val cameraYawRad = atan2(cameraZ.z, cameraZ.x)

		val extraYaw = QuaternionD.rotationAroundYAxis(cameraYawRad - yawRad)
		val newCameraToWorld = extraYaw * camera.extrinsic.cameraToWorld

		val newCameraOriginInWorld = lookAt - newCameraToWorld.sandwichUnitZ() * distance

		return Camera(
			Camera.Extrinsic.fromCameraPose(newCameraToWorld, newCameraOriginInWorld),
			camera.intrinsic,
			camera.imageSize,
		)
	}

	private data class CameraSolution(
		val rms: Double,
		val camera: Camera,
		val wristInController: Vector3D,
		val controllerDelay: Duration,
	)

	private fun solveCamera(
		initialCamera: Camera,
		controllerDelay: Duration,
		matches: List<Match>,
	): CameraSolution? {
		val n = matches.size * 2 + 1

		val costFn = MultivariateVectorFunction { params ->
			val (camera, wristInController) = decodeParams(params, initialCamera)

			val residuals = DoubleArray(n) { 0.0 }
			var i = 0

			// Residual between projected tracker and camera wrist
			for (match in matches) {
				val projected = projectWrist(camera, match.tracker, wristInController)
				if (projected != null) {
					val dx = projected.x - match.joint.x
					val dy = projected.y - match.joint.y
					residuals[i++] = dx
					residuals[i++] = dy
				} else {
					residuals[i++] = 1.0e6
					residuals[i++] = 1.0e6
				}
			}

			// Prevent controller to wrist from growing too big
			residuals[i++] = max(wristInController.len() - MAX_CONTROLLER_TO_WRIST_LENGTH_M, 0.0)

			residuals
		}

		val model = numericalJacobian(costFn)

		val initial = doubleArrayOf(
			0.0, // Extra camera yaw
			0.0, // Extra camera translation X
			0.0, // Extra camera translation Y
			0.0, // Extra camera translation Z
			0.0, // Tracker to wrist x
			0.0, // Tracker to wrist y
			0.0, // Tracker to wrist z
		)

		val problem = LeastSquaresBuilder()
			.start(initial)
			.model(model)
			.target(DoubleArray(n) { 0.0 })
			.maxEvaluations(10000)
			.maxIterations(10000)
			.build()

		val result: LeastSquaresOptimizer.Optimum
		try {
			result = LevenbergMarquardtOptimizer().optimize(problem)
		} catch (e: Exception) {
			return null
		}

		val decodedParams = decodeParams(result.point.toArray(), initialCamera)

		return CameraSolution(result.rms, decodedParams.camera, decodedParams.wristInController, controllerDelay)
	}

	data class DecodedParams(
		val camera: Camera,
		val wristInController: Vector3D,
	)

	private fun decodeParams(params: DoubleArray, initialCamera: Camera): DecodedParams {
		val extraCameraYaw = QuaternionD.rotationAroundYAxis(params[0])
		val extraCameraTranslation = Vector3D(params[1], params[2], params[3])
		val wristInController = Vector3D(params[4], params[5], params[6])

		val camera =
			Camera(
				Camera.Extrinsic.fromCameraPose(
					extraCameraYaw * initialCamera.extrinsic.cameraToWorld,
					initialCamera.extrinsic.cameraOriginInWorld + extraCameraTranslation,
				),
				initialCamera.intrinsic,
				initialCamera.imageSize,
			)

		return DecodedParams(camera, wristInController)
	}

	private fun projectWrist(camera: Camera, tracker: PositionalTracker, wristInTracker: Vector3D): Vector2D? {
		val wrist = tracker.trackerOriginInOVRWorld + tracker.trackerToOVRWorld.sandwich(wristInTracker)
		return camera.project(wrist)
	}

	companion object {
		private const val MIN_DISTANCE_BETWEEN_MATCH_M = 0.05
		private const val MIN_MATCHES = 150
		private const val MAX_CONTROLLER_DELAY_MS = 500
		private const val CONTROLLER_DELAY_STEP_MS = 20
		private const val CAMERA_YAW_STEP_DEG = 90
		private const val INITIAL_CAMERA_DISTANCE_M = 2.0
		private const val MAX_CONTROLLER_TO_WRIST_LENGTH_M = 0.3
	}
}
