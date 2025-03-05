package dev.slimevr.math

import com.jme3.math.FastMath
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.math.*

@JvmInline
value class Angle(val rad: Float) {

	fun toRad() = rad
	fun toDeg() = rad * FastMath.RAD_TO_DEG

	/**
	 * Normalizes the angle to between [0, 2*PI)
	 */
	fun normalized() =
		if (rad < 0.0f || rad >= FastMath.TWO_PI) {
			Angle(rad - floor(rad * FastMath.INV_TWO_PI) * FastMath.TWO_PI)
		} else {
			this
		}

	/**
	 * Normalizes the angle to between [-PI, PI)
	 */
	fun normalizedAroundZero() =
		normalized().let {
			if (it.rad > FastMath.PI) {
				Angle(it.rad - FastMath.TWO_PI)
			} else {
				it
			}
		}

	fun near(other: Angle, error: Angle = DEFAULT_NEAR_ANGLE) =
		abs(this - other) < error

	operator fun unaryPlus() = Angle(rad)
	operator fun unaryMinus() = Angle(-rad)
	operator fun plus(other: Angle) = Angle(rad + other.rad)
	operator fun minus(other: Angle) = Angle(rad - other.rad)
	operator fun times(scale: Float) = Angle(rad * scale)
	operator fun div(scale: Float) = Angle(rad / scale)

	operator fun compareTo(other: Angle): Int = rad.compareTo(other.rad)
	override fun toString(): String = "${toDeg()} deg"

	fun toDegreeAngle() =
		DegreeAngle(toDeg())

	companion object {
		val ZERO = Angle(0.0f)

		fun ofRad(rad: Float) = Angle(rad)
		fun ofDeg(deg: Float) = Angle(deg * FastMath.DEG_TO_RAD)

		fun abs(angle: Angle) = ofRad(abs(angle.rad))

		// Signed angle between two angles
		fun signedBetween(a: Angle, b: Angle) =
			ofRad(a.rad - b.rad).normalizedAroundZero()

		// Angle between two vectors
		fun absBetween(a: Vector3, b: Vector3) =
			ofRad(a.angleTo(b))

		// Angle between two rotations in rotation space
		fun absBetween(a: Quaternion, b: Quaternion) =
			ofRad(a.angleToR(b))

		private val DEFAULT_NEAR_ANGLE = ofRad(1e-6f)
	}
}
