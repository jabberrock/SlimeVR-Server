package dev.slimevr.tracking.processor.stayaligned.skeleton

import dev.slimevr.config.StayAlignedConfig
import dev.slimevr.math.Angle
import dev.slimevr.tracking.processor.stayaligned.StayAlignedDefaults

class RelaxedBodyAngles(
	val upperLeg: Angle,
	val lowerLeg: Angle,
	val foot: Angle,
) {
	fun near(other: RelaxedBodyAngles) =
		upperLeg.near(other.upperLeg) && lowerLeg.near(other.lowerLeg) && foot.near(other.foot)

	companion object {

		/**
		 * Gets the relaxed angles for a particular pose. May provide defaults if the
		 * angles aren't configured for the pose.
		 */
		fun forPose(
			pose: TrackerSkeletonPose,
			config: StayAlignedConfig,
		) =
			when (pose) {
				TrackerSkeletonPose.STANDING ->
					RelaxedBodyAngles(
						config.standingUpperLegAngle.toAngle(),
						config.standingLowerLegAngle.toAngle(),
						config.standingFootAngle.toAngle(),
					)

				TrackerSkeletonPose.SITTING_IN_CHAIR ->
					RelaxedBodyAngles(
						config.sittingUpperLegAngle.toAngle(),
						config.sittingLowerLegAngle.toAngle(),
						config.sittingFootAngle.toAngle(),
					)

				TrackerSkeletonPose.SITTING_ON_GROUND,
				TrackerSkeletonPose.LYING_ON_BACK,
				->
					RelaxedBodyAngles(
						config.flatUpperLegAngle.toAngle(),
						config.flatLowerLegAngle.toAngle(),
						config.flatFootAngle.toAngle(),
					)

				TrackerSkeletonPose.KNEELING ->
					StayAlignedDefaults.RELAXED_BODY_ANGLES_KNEELING

				else ->
					null
			}
	}
}
