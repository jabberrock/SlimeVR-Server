package dev.slimevr.tracking.videocalibration.preview

import dev.slimevr.tracking.videocalibration.util.OnlineService
import kotlinx.coroutines.channels.SendChannel
import java.awt.image.BufferedImage

interface VideoSink : OnlineService {
	val outgoingVideo: SendChannel<BufferedImage>
}
