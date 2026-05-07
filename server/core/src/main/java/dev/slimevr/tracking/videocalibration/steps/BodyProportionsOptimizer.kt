package dev.slimevr.tracking.videocalibration.steps

import dev.slimevr.tracking.processor.Bone
import dev.slimevr.tracking.processor.config.SkeletonConfigManager.Companion.HEIGHT_OFFSETS
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.processor.skeleton.refactor.Skeleton
import dev.slimevr.tracking.processor.skeleton.refactor.SkeletonUpdater
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.Database
import dev.slimevr.tracking.videocalibration.TrackerResetOverride
import dev.slimevr.tracking.videocalibration.human.HumanJoint
import dev.slimevr.tracking.videocalibration.trackers.IMUTracker
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshot
import dev.slimevr.tracking.videocalibration.util.DebugOutput
import dev.slimevr.tracking.videocalibration.util.numericalJacobian
import dev.slimevr.tracking.videocalibration.vision.Camera
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.Vector2D
import org.apache.commons.math3.analysis.MultivariateVectorFunction
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer
import kotlin.time.Duration

class BodyProportionsOptimizer(
	private val debugOutput: DebugOutput,
) {

	class Solution(
		val skeletonOffsets: Map<SkeletonConfigOffsets, Float>,
	)

	private val skeleton = Skeleton(false, false)

	fun solve(
		database: Database,
		camera: Camera,
		cameraDelay: Duration,
		initialHmdHeight: Float,
		initialSkeletonOffsets: Map<SkeletonConfigOffsets, Float>,
		trackerAlignments: Map<TrackerPosition, TrackerResetOverride>,
	): Solution? {
		// TODO: Use delays for each tracker instead of using cameraDelay
		val snapshots = database.matchAll(cameraDelay, mapOf())

		val alignedSnapshots = snapshots.map { (humanPose, snapshot) ->
			Database.Match(humanPose, alignSnapshot(snapshot, trackerAlignments))
		}

		val filteredAlignedSnapshots = alignedSnapshots.filterIndexed { index, _ -> index % SKIP_FRAMES == 0 }

		LogManager.info("Solving skeleton offsets with ${filteredAlignedSnapshots.size} frames...")

		if (filteredAlignedSnapshots.size < MIN_FRAMES) {
			return null
		}

		val n = 1 + filteredAlignedSnapshots.size * NUM_JOINTS * 2

		val costFn = MultivariateVectorFunction { p ->
			var index = 0
			val residuals = DoubleArray(n) { 0.0 }

			val config = SkeletonUpdater.HumanSkeletonConfig()
			val skeletonOffsets = makeSkeletonOffsets(p, initialSkeletonOffsets)

			for (frame in filteredAlignedSnapshots) {
				val (humanPoseSnapshot, trackersSnapshot) = frame
				val trackersData = SkeletonUpdater.TrackersData.fromSnapshot(trackersSnapshot)
				val joints = humanPoseSnapshot.joints

				val skeletonUpdater = SkeletonUpdater(skeleton, trackersData, config, skeletonOffsets)
				skeletonUpdater.update()

				val leftShoulderJoint = joints[HumanJoint.LEFT_SHOULDER]
				val rightShoulderJoint = joints[HumanJoint.RIGHT_SHOULDER]

				index = addResidual(residuals, index, camera, skeleton.leftUpperArmBone, leftShoulderJoint)
				index = addResidual(residuals, index, camera, skeleton.rightUpperArmBone, rightShoulderJoint)

				if (leftShoulderJoint != null && rightShoulderJoint != null) {
					index = addResidual(residuals, index, camera, skeleton.upperChestBone, (leftShoulderJoint + rightShoulderJoint) * 0.5)
				}

				index = addResidual(residuals, index, camera, skeleton.leftUpperLegBone, joints[HumanJoint.LEFT_HIP])
				index = addResidual(residuals, index, camera, skeleton.leftLowerLegBone, joints[HumanJoint.LEFT_KNEE])
				index = addResidual(residuals, index, camera, skeleton.leftFootBone, joints[HumanJoint.LEFT_ANKLE])

				index = addResidual(residuals, index, camera, skeleton.rightUpperLegBone, joints[HumanJoint.RIGHT_HIP])
				index = addResidual(residuals, index, camera, skeleton.rightLowerLegBone, joints[HumanJoint.RIGHT_KNEE])
				index = addResidual(residuals, index, camera, skeleton.rightFootBone, joints[HumanJoint.RIGHT_ANKLE])

				if (trackerAlignments.containsKey(TrackerPosition.LEFT_UPPER_ARM)) {
					index = addResidual(residuals, index, camera, skeleton.leftUpperArmBone, joints[HumanJoint.LEFT_SHOULDER])
					if (trackerAlignments.containsKey(TrackerPosition.LEFT_LOWER_ARM)) {
						index = addResidual(residuals, index, camera, skeleton.leftLowerArmBone, joints[HumanJoint.LEFT_ELBOW])
						index = addResidual(residuals, index, camera, skeleton.leftHandBone, joints[HumanJoint.LEFT_WRIST])
					}
				}

				if (trackerAlignments.containsKey(TrackerPosition.RIGHT_UPPER_ARM)) {
					index = addResidual(residuals, index, camera, skeleton.rightUpperArmBone, joints[HumanJoint.RIGHT_SHOULDER])
					if (trackerAlignments.containsKey(TrackerPosition.RIGHT_LOWER_ARM)) {
						index = addResidual(residuals, index, camera, skeleton.rightLowerArmBone, joints[HumanJoint.RIGHT_ELBOW])
						index = addResidual(residuals, index, camera, skeleton.rightHandBone, joints[HumanJoint.RIGHT_WRIST])
					}
				}
			}

			val hmdHeight = HEIGHT_OFFSETS.map { skeletonOffsets[it] ?: 0.0f }.reduce { acc, v -> acc + v } + ANKLE_TO_HEEL_LENGTH
			residuals[index] =
				(hmdHeight - initialHmdHeight.toDouble()) *
				filteredAlignedSnapshots.size *
				HEIGHT_RESIDUAL_WEIGHT

			residuals
		}

		val model = numericalJacobian(costFn)

		// TODO: Use params
		val initial = doubleArrayOf(
			0.7, // TORSO
			0.35, // HIPS_WIDTH
			0.5, // UPPER_LEG
			0.5, // LOWER_LEG
			0.15, // NECK + SHOULDER_DISTANCE
			0.20, // HEAD
			0.35, // SHOULDER_WIDTH,
			0.25, // UPPER_ARM_LENGTH,
			0.25, // LOWER_ARM_LENGTH,
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
			LogManager.warning("Failed to solve skeleton offsets: $e", e)
			return null
		}

		val skeletonOffsets = makeSkeletonOffsets(result.point.toArray(), initialSkeletonOffsets)

		// Adjust ankle to the ground
		val lowerLegLength = skeletonOffsets[SkeletonConfigOffsets.LOWER_LEG]
		if (lowerLegLength != null) {
			skeletonOffsets[SkeletonConfigOffsets.LOWER_LEG] = lowerLegLength + ANKLE_TO_HEEL_LENGTH.toFloat()
		}

		fun printSkeletonOffset(skeletonOffset: SkeletonConfigOffsets) {
			val formatter = "%.2f"
			LogManager.info("${skeletonOffset.name.padStart(15, ' ')}: ${formatter.format(skeletonOffsets[skeletonOffset])} (was ${formatter.format(initialSkeletonOffsets[skeletonOffset])})")
		}

		LogManager.info("Solved skeleton offsets:")
		printSkeletonOffset(SkeletonConfigOffsets.HEAD)
		printSkeletonOffset(SkeletonConfigOffsets.NECK)
		printSkeletonOffset(SkeletonConfigOffsets.UPPER_CHEST)
		printSkeletonOffset(SkeletonConfigOffsets.CHEST)
		printSkeletonOffset(SkeletonConfigOffsets.WAIST)
		printSkeletonOffset(SkeletonConfigOffsets.HIP)
		printSkeletonOffset(SkeletonConfigOffsets.HIPS_WIDTH)
		printSkeletonOffset(SkeletonConfigOffsets.UPPER_LEG)
		printSkeletonOffset(SkeletonConfigOffsets.LOWER_LEG)
		printSkeletonOffset(SkeletonConfigOffsets.SHOULDERS_DISTANCE)
		printSkeletonOffset(SkeletonConfigOffsets.SHOULDERS_WIDTH)
		printSkeletonOffset(SkeletonConfigOffsets.UPPER_ARM)
		printSkeletonOffset(SkeletonConfigOffsets.LOWER_ARM)

		return Solution(skeletonOffsets)
	}

	private fun makeSkeletonOffsets(
		p: DoubleArray,
		initialSkeletonOffsets: Map<SkeletonConfigOffsets, Float>,
	): MutableMap<SkeletonConfigOffsets, Float> {
		val torsoLength = p[0]
		val offsets = initialSkeletonOffsets.toMutableMap().apply {
			this[SkeletonConfigOffsets.UPPER_CHEST] = (UPPER_CHEST_RATIO * torsoLength).toFloat()
			this[SkeletonConfigOffsets.CHEST] = (CHEST_RATIO * torsoLength).toFloat()
			this[SkeletonConfigOffsets.WAIST] = (WAIST_RATIO * torsoLength).toFloat()
			this[SkeletonConfigOffsets.HIP] = (HIP_RATIO * torsoLength).toFloat()
			this[SkeletonConfigOffsets.HIPS_WIDTH] = p[1].toFloat()
			this[SkeletonConfigOffsets.UPPER_LEG] = p[2].toFloat()
			this[SkeletonConfigOffsets.LOWER_LEG] = p[3].toFloat()
			// Share the neck and shoulders distance
			this[SkeletonConfigOffsets.NECK] = (p[4] * NECK_RATIO).toFloat()
			this[SkeletonConfigOffsets.SHOULDERS_DISTANCE] = (p[4] * SHOULDER_DISTANCE_RATIO).toFloat()
			this[SkeletonConfigOffsets.HEAD] = p[5].toFloat()
			this[SkeletonConfigOffsets.SHOULDERS_WIDTH] = p[6].toFloat()
			this[SkeletonConfigOffsets.UPPER_ARM] = p[7].toFloat()
			this[SkeletonConfigOffsets.LOWER_ARM] = p[8].toFloat()
		}
		return offsets
	}

	private fun addResidual(residuals: DoubleArray, index: Int, camera: Camera, bone: Bone, joint: Vector2D?): Int {
		if (joint == null) {
			return index
		}

		val estimated = camera.project(bone.getPosition().toDouble())
		if (estimated == null) {
			return index
		}

		residuals[index + 0] = joint.x - estimated.x
		residuals[index + 1] = joint.y - estimated.y

		return index + 2
	}

	private fun alignSnapshot(
		snapshot: TrackersSnapshot,
		trackerAlignments: Map<TrackerPosition, TrackerResetOverride>,
	): TrackersSnapshot {
		val alignedTrackers = snapshot.imuTrackers.mapValues { (trackerPosition, tracker) ->
			val alignment = trackerAlignments[trackerPosition]!!
			IMUTracker(alignment.toBoneRotation(tracker.trackerToArbitraryWorld))
		}

		return TrackersSnapshot(snapshot.timestamp, snapshot.controllers, alignedTrackers)
	}

	companion object {
		private const val SKIP_FRAMES = 3
		private const val MIN_FRAMES = 100

		private const val UPPER_CHEST_RATIO = 0.25
		private const val CHEST_RATIO = 0.25
		private const val WAIST_RATIO = 0.25
		private const val HIP_RATIO = 0.25

		private const val NECK_RATIO = 0.3
		private const val SHOULDER_DISTANCE_RATIO = 0.7

		private const val ANKLE_TO_HEEL_LENGTH = 0.06

		private const val NUM_JOINTS = 15

		private const val HEIGHT_RESIDUAL_WEIGHT = 1000.0
	}
}
