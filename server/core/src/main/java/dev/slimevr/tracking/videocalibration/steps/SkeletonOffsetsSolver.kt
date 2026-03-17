package dev.slimevr.tracking.videocalibration.steps

import dev.slimevr.tracking.processor.Bone
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.processor.skeleton.refactor.Skeleton
import dev.slimevr.tracking.processor.skeleton.refactor.SkeletonUpdater
import dev.slimevr.tracking.videocalibration.data.Camera
import dev.slimevr.tracking.videocalibration.data.CocoWholeBodyKeypoint
import dev.slimevr.tracking.videocalibration.snapshots.HumanPoseSnapshot
import dev.slimevr.tracking.videocalibration.snapshots.TrackersSnapshot
import dev.slimevr.tracking.videocalibration.util.DebugOutput
import dev.slimevr.tracking.videocalibration.util.numericalJacobian
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.Vector2D
import org.apache.commons.math3.analysis.MultivariateVectorFunction
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresBuilder
import org.apache.commons.math3.fitting.leastsquares.LeastSquaresOptimizer
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer

class SkeletonOffsetsSolver(
	private val debugOutput: DebugOutput
) {

	class Solution(
		val skeletonOffsets: Map<SkeletonConfigOffsets, Float>
	)

	private val skeleton = Skeleton(false, false)

	fun solve(
		frames: List<Pair<TrackersSnapshot, HumanPoseSnapshot>>,
		camera: Camera,
		initialSkeletonOffsets: Map<SkeletonConfigOffsets, Float>
	): Solution? {

		if (frames.size < MIN_FRAMES) {
			return null
		}

		val adjustedFrames = frames.map { (trackersSnapshot, humanPoseSnapshot) ->
			SkeletonUpdater.TrackersData.fromSnapshot(trackersSnapshot) to humanPoseSnapshot
		}

		val n = adjustedFrames.size * NUM_JOINTS * 2

		val costFn = MultivariateVectorFunction { p ->
			var index = 0
			val residuals = DoubleArray(n) { 0.0 }

			val config = SkeletonUpdater.HumanSkeletonConfig()
			val skeletonOffsets = makeSkeletonOffsets(p, initialSkeletonOffsets)
			for (frame in adjustedFrames) {
				val (trackersData, humanPoseSnapshot) = frame
				val joints = humanPoseSnapshot.joints

				val skeletonUpdater = SkeletonUpdater(skeleton, trackersData, config, skeletonOffsets)
				skeletonUpdater.update()

				index = addResidual(residuals, index, camera, skeleton.leftUpperLegBone, joints[CocoWholeBodyKeypoint.LEFT_HIP])
				index = addResidual(residuals, index, camera, skeleton.leftLowerLegBone, joints[CocoWholeBodyKeypoint.LEFT_KNEE])
				index = addResidual(residuals, index, camera, skeleton.leftFootBone, joints[CocoWholeBodyKeypoint.LEFT_ANKLE])

				index = addResidual(residuals, index, camera, skeleton.rightUpperLegBone, joints[CocoWholeBodyKeypoint.RIGHT_HIP])
				index = addResidual(residuals, index, camera, skeleton.rightLowerLegBone, joints[CocoWholeBodyKeypoint.RIGHT_KNEE])
				index = addResidual(residuals, index, camera, skeleton.rightFootBone, joints[CocoWholeBodyKeypoint.RIGHT_ANKLE])
			}

			return@MultivariateVectorFunction residuals
		}

		val model = numericalJacobian(costFn)

		// TODO: Use params
		val initial = doubleArrayOf(
			0.7, // TORSO
			0.35, // HIPS_WIDTH
			0.5, // UPPER_LEG
			0.5, // LOWER_LEG
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

		val lowerLegLength = skeletonOffsets[SkeletonConfigOffsets.LOWER_LEG]
		if (lowerLegLength != null) {
			skeletonOffsets[SkeletonConfigOffsets.LOWER_LEG] = lowerLegLength + ANKLE_TO_HEEL_LENGTH.toFloat()
		}

		fun printSkeletonOffset(skeletonOffset: SkeletonConfigOffsets) {
			val formatter = "%.2f"
			LogManager.info("${skeletonOffset.name.padStart(15, ' ')}: ${formatter.format(skeletonOffsets[skeletonOffset])} (was ${formatter.format(initialSkeletonOffsets[skeletonOffset])})")
		}

		LogManager.info("Solved skeleton offsets:")
		printSkeletonOffset(SkeletonConfigOffsets.UPPER_CHEST)
		printSkeletonOffset(SkeletonConfigOffsets.CHEST)
		printSkeletonOffset(SkeletonConfigOffsets.WAIST)
		printSkeletonOffset(SkeletonConfigOffsets.HIP)
		printSkeletonOffset(SkeletonConfigOffsets.HIPS_WIDTH)
		printSkeletonOffset(SkeletonConfigOffsets.UPPER_LEG)
		printSkeletonOffset(SkeletonConfigOffsets.LOWER_LEG)

		debugOutput.saveSkeletonOffsets(camera, frames, initialSkeletonOffsets, skeletonOffsets)

		return Solution(skeletonOffsets)
	}

	private fun makeSkeletonOffsets(
		p: DoubleArray,
		initialSkeletonOffsets: Map<SkeletonConfigOffsets, Float>
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

	companion object {
		private const val MIN_FRAMES = 400

		private const val UPPER_CHEST_RATIO = 0.30
		private const val CHEST_RATIO = 0.30
		private const val WAIST_RATIO = 0.32
		private const val HIP_RATIO = 0.08

		private const val ANKLE_TO_HEEL_LENGTH = 0.08

		private const val NUM_JOINTS = 6
	}
}
