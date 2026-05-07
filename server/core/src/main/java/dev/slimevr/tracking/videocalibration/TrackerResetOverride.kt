package dev.slimevr.tracking.videocalibration

import io.github.axisangles.ktmath.EulerAnglesD
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.QuaternionD
import io.github.axisangles.ktmath.toEulerYZXString

/**
 * Converts a tracker's orientation into the corresponding orientation of the bone.
 */
data class TrackerResetOverride(
	val globalYaw: Double,
	val localYaw: Double,
	val localRoll: Double,
	val localPitch: Double,
) {
	private val globalRotation = QuaternionD.Companion.rotationAroundYAxis(globalYaw)
	private val localRotation = EulerAnglesD(EulerOrder.YZX, localYaw, localRoll, localPitch).toQuaternion()

	fun toBoneRotation(trackerRotation: QuaternionD): QuaternionD {
		val rotation = (globalRotation * trackerRotation * localRotation).twinNearest(QuaternionD.Companion.IDENTITY)
		return rotation
	}

	fun toBoneRotation(trackerRotation: Quaternion): Quaternion {
		val rotation = toBoneRotation(trackerRotation.toDouble())
		return rotation.toFloat()
	}

	override fun toString() = "TrackerReset(global_yaw=${globalRotation.toEulerYZXString()} local=${localRotation.toEulerYZXString()})"
}
