package dev.slimevr.tracking.videocalibration.vision

import io.github.axisangles.ktmath.QuaternionD
import io.github.axisangles.ktmath.Vector2D
import io.github.axisangles.ktmath.Vector3D
import io.github.axisangles.ktmath.toEulerYZXString
import java.awt.Dimension

class Camera(
	val extrinsic: Extrinsic,
	val intrinsic: Intrinsic,
	val imageSize: Dimension,
) {
	/**
	 * Projects a point in world space into the camera's image plane.
	 */
	fun project(pointInWorld: Vector3D) = intrinsic.project(extrinsic.toCamera(pointInWorld))

	/**
	 * Finds the image vector that corresponds to vector in world space.
	 */
	fun project(vectorInWorld: Vector3D, originInImage: Vector2D, depth: Double): Vector2D? {
		val originInWorld = extrinsic.toWorld(intrinsic.ray(originInImage) * depth)
		val tipInWorld = originInWorld + vectorInWorld
		val tipInImage = project(tipInWorld) ?: return null
		return tipInImage - originInImage
	}

	override fun toString() = "Camera($extrinsic $intrinsic $imageSize)"

	/**
	 * A camera's position and orientation.
	 */
	class Extrinsic {
		val worldToCamera: QuaternionD
		val worldOriginInCamera: Vector3D
		val cameraToWorld: QuaternionD
		val cameraOriginInWorld: Vector3D

		private constructor(worldToCamera: QuaternionD, worldOriginInCamera: Vector3D) {
			this.worldToCamera = worldToCamera
			this.worldOriginInCamera = worldOriginInCamera
			this.cameraToWorld = worldToCamera.inv()
			this.cameraOriginInWorld = -cameraToWorld.sandwich(worldOriginInCamera)
		}

		/**
		 * Transforms a point in world space to camera space.
		 */
		fun toCamera(pointInWorld: Vector3D) = worldToCamera.sandwich(pointInWorld) + worldOriginInCamera

		/**
		 * Transforms a point in camera space to world space.
		 */
		fun toWorld(pointInCamera: Vector3D) = cameraToWorld.sandwich(pointInCamera) + cameraOriginInWorld

		override fun toString() = "Extrinsic(cameraToWorld=${cameraToWorld.toEulerYZXString()} cameraOriginInWorld=$cameraOriginInWorld)"

		companion object {

			fun fromWorldPose(worldToCamera: QuaternionD, worldOriginInCamera: Vector3D) = Extrinsic(worldToCamera, worldOriginInCamera)

			fun fromCameraPose(cameraToWorld: QuaternionD, cameraOriginInWorld: Vector3D): Extrinsic {
				val worldToCamera = cameraToWorld.inv()
				return Extrinsic(worldToCamera, -worldToCamera.sandwich(cameraOriginInWorld))
			}
		}
	}

	/**
	 * A camera's internal parameters for projecting points in camera space to the camera's image plane.
	 */
	class Intrinsic(
		val fx: Double,
		val fy: Double,
		val tx: Double,
		val ty: Double,
	) {
		/**
		 * Projects a point in camera space into the camera's image space.
		 */
		fun project(pointInCamera: Vector3D): Vector2D? {
			if (pointInCamera.z < 0.1) {
				return null
			}

			return Vector2D(
				pointInCamera.x / pointInCamera.z * fx + tx,
				pointInCamera.y / pointInCamera.z * fy + ty,
			)
		}

		/**
		 * A ray from the camera center to the point on the image plane.
		 */
		fun ray(imagePoint: Vector2D): Vector3D = Vector3D(
			(imagePoint.x - tx) / fx,
			(imagePoint.y - ty) / fy,
			1.0,
		)

		override fun toString() = "Intrinsic(fx=${"%.1f".format(fx)} fy=${"%.1f".format(fy)} tx=${"%.1f".format(tx)} ty=${"%.1f".format(ty)})"
	}
}
