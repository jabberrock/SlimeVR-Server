package dev.slimevr.config

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import dev.slimevr.config.serializers.DegreeAngleConversions
import dev.slimevr.math.DegreeAngle

class StayAlignedConfig {

	// Apply yaw correction
	var enabled = false

	// Amount of yaw correction
	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var yawCorrectionPerSec = DegreeAngle(0.2f)

	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var standingUpperLegAngle = DegreeAngle(0.0f)

	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var standingLowerLegAngle = DegreeAngle(0.0f)

	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var standingFootAngle = DegreeAngle(0.0f)

	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var sittingUpperLegAngle = DegreeAngle(0.0f)

	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var sittingLowerLegAngle = DegreeAngle(0.0f)

	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var sittingFootAngle = DegreeAngle(0.0f)

	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var flatUpperLegAngle = DegreeAngle(0.0f)

	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var flatLowerLegAngle = DegreeAngle(0.0f)

	@JsonSerialize(using = DegreeAngleConversions.Serializer::class)
	@JsonDeserialize(using = DegreeAngleConversions.Deserializer::class)
	var flatFootAngle = DegreeAngle(0.0f)

	fun clone() =
		StayAlignedConfig().also {
			it.enabled = enabled
			it.yawCorrectionPerSec = yawCorrectionPerSec
			it.standingUpperLegAngle = standingUpperLegAngle
			it.standingLowerLegAngle = standingLowerLegAngle
			it.standingFootAngle = standingFootAngle
			it.sittingUpperLegAngle = sittingUpperLegAngle
			it.sittingLowerLegAngle = sittingLowerLegAngle
			it.sittingFootAngle = sittingFootAngle
			it.flatUpperLegAngle = flatUpperLegAngle
			it.flatLowerLegAngle = flatLowerLegAngle
			it.flatFootAngle = flatFootAngle
		}
}
