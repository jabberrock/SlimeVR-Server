package dev.slimevr.tracking.videocalibration.vision

import java.awt.image.BufferedImage
import kotlin.time.TimeSource

class VideoImage(
	val timestamp: TimeSource.Monotonic.ValueTimeMark,
	val image: BufferedImage,
)
