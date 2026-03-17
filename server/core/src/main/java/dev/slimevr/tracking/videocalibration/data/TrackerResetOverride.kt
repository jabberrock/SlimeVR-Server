package dev.slimevr.tracking.videocalibration.data

import dev.slimevr.tracking.videocalibration.util.toEulerYZXString
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.QuaternionD

/**
 * Converts a tracker's rotation into its bone's rotation.
 */
data class TrackerResetOverride(
	val globalYaw: Double,
	val localRotation: QuaternionD,
) {
	private val globalRotation = QuaternionD.rotationAroundYAxis(globalYaw)

	fun toBoneRotation(trackerRotation: QuaternionD) = globalRotation * trackerRotation * localRotation

	fun toBoneRotation(trackerRotation: Quaternion) = toBoneRotation(trackerRotation.toDouble()).toFloat()

	override fun toString() = "TrackerReset(global_yaw=${globalRotation.toEulerYZXString()} local=${localRotation.toEulerYZXString()})"
}
