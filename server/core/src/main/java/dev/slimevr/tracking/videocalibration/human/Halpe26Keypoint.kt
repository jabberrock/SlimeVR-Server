package dev.slimevr.tracking.videocalibration.human

enum class Halpe26Keypoint(val index: Int) {
	NOSE(0),
	LEFT_EYE(1),
	RIGHT_EYE(2),
	LEFT_EAR(3),
	RIGHT_EAR(4),
	LEFT_SHOULDER(5),
	RIGHT_SHOULDER(6),
	LEFT_ELBOW(7),
	RIGHT_ELBOW(8),
	LEFT_WRIST(9),
	RIGHT_WRIST(10),
	LEFT_HIP(11),
	RIGHT_HIP(12),
	LEFT_KNEE(13),
	RIGHT_KNEE(14),
	LEFT_ANKLE(15),
	RIGHT_ANKLE(16),
	HEAD(17),
	NECK(18),
	HIP(19),
	LEFT_BIG_TOE(20),
	RIGHT_BIG_TOE(21),
	LEFT_SMALL_TOE(22),
	RIGHT_SMALL_TOE(23),
	LEFT_HEEL(24),
	RIGHT_HEEL(25),
	;

	companion object {
		val humanJoint = mapOf(
			LEFT_SHOULDER to HumanJoint.LEFT_SHOULDER,
			RIGHT_SHOULDER to HumanJoint.RIGHT_SHOULDER,
			LEFT_HIP to HumanJoint.LEFT_HIP,
			RIGHT_HIP to HumanJoint.RIGHT_HIP,
			LEFT_KNEE to HumanJoint.LEFT_KNEE,
			RIGHT_KNEE to HumanJoint.RIGHT_KNEE,
			LEFT_ANKLE to HumanJoint.LEFT_ANKLE,
			RIGHT_ANKLE to HumanJoint.RIGHT_ANKLE,
			LEFT_HEEL to HumanJoint.LEFT_HEEL,
			RIGHT_HEEL to HumanJoint.RIGHT_HEEL,
			LEFT_BIG_TOE to HumanJoint.LEFT_BIG_TOE,
			RIGHT_BIG_TOE to HumanJoint.RIGHT_BIG_TOE,
			LEFT_SMALL_TOE to HumanJoint.LEFT_SMALL_TOE,
			RIGHT_SMALL_TOE to HumanJoint.RIGHT_SMALL_TOE,
			LEFT_ELBOW to HumanJoint.LEFT_ELBOW,
			RIGHT_ELBOW to HumanJoint.RIGHT_ELBOW,
			LEFT_WRIST to HumanJoint.LEFT_WRIST,
			RIGHT_WRIST to HumanJoint.RIGHT_WRIST,
		)

		val indexToHumanJoint = humanJoint.mapKeys { it.key.index }
	}
}
