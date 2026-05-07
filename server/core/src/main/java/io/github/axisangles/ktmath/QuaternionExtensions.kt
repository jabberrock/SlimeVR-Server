package io.github.axisangles.ktmath

import com.jme3.math.FastMath

fun Quaternion.toEulerYZXString(): String {
	val yzx = toEulerAngles(EulerOrder.YZX)
	return "(y=${"%.1f".format(yzx.y * FastMath.RAD_TO_DEG)}, z=${"%.1f".format(yzx.z * FastMath.RAD_TO_DEG)} x=${"%.1f".format(yzx.x * FastMath.RAD_TO_DEG)})"
}

fun QuaternionD.toEulerYZXString(): String {
	val yzx = toEulerAngles(EulerOrder.YZX)
	return "(y=${"%.1f".format(yzx.y * FastMath.RAD_TO_DEG)}, z=${"%.1f".format(yzx.z * FastMath.RAD_TO_DEG)} x=${"%.1f".format(yzx.x * FastMath.RAD_TO_DEG)})"
}

fun Quaternion.toAngleAxisString(): String {
	val axisAngle = toRotationVector()
	val angle = axisAngle.len()
	val axis = if (angle >= 1e-12f) axisAngle.unit() else Vector3D.POS_Y
	return "($axis, ${"%.1f".format(angle * FastMath.RAD_TO_DEG)})"
}

fun QuaternionD.toAngleAxisString(): String {
	val axisAngle = toRotationVector()
	val angle = axisAngle.len()
	val axis = if (angle >= 1e-12) axisAngle.unit() else Vector3D.POS_Y
	return "($axis, ${"%.1f".format(angle * FastMath.RAD_TO_DEG)})"
}
