package dev.slimevr.tracking.videocalibration.preview

import dev.slimevr.tracking.videocalibration.human.HumanPoseOverlay
import dev.slimevr.tracking.videocalibration.human.HumanPoseSnapshot
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshot
import dev.slimevr.tracking.videocalibration.vision.Camera
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference

class PreviewGenerator {

	private val camera = AtomicReference<Camera?>(null)

	fun draw(
		image: BufferedImage,
		humanPoseSnapshot: HumanPoseSnapshot,
		trackersSnapshot: TrackersSnapshot?,
	) {
		HumanPoseOverlay.draw(image, humanPoseSnapshot.joints)

// 		val camera = camera.get()
// 		if (camera != null) {
// 			if (trackersSnapshot != null) {
// 				HumanPoseOverlay.drawControllers(image, camera, trackersSnapshot)
// 			}
//
// //			val g = image.createGraphics()
// //			g.color = Color.RED
// //			g.stroke = BasicStroke(1.0f)
// //			for (i in 0 until camera.imageSize.width step 30) {
// //				val start = Vector2D(i.toDouble(), 0.0)
// //				val dir = camera.project(Vector3D.NEG_Y, start, 1.0)
// //				if (dir != null) {
// //					val end = start + dir * (camera.imageSize.height.toDouble() / dir.y)
// //					g.drawLine(start.x.toInt(), start.y.toInt(), end.x.toInt(), end.y.toInt())
// //				}
// //			}
// //			g.dispose()
// 		}
	}

	fun setCamera(camera: Camera) {
		this.camera.set(camera)
	}
}
