package dev.slimevr.math

import com.jme3.math.FastMath

data class DegreeAngle(val deg: Float) {

	fun toDeg() = deg
	fun toRad() = deg * FastMath.DEG_TO_RAD

	fun toAngle() = Angle(toRad())

	companion object {
		val ZERO = DegreeAngle(0.0f)
	}
}
