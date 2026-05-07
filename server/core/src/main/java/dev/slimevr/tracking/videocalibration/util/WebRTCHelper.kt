package dev.slimevr.tracking.videocalibration.util

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.awt.image.BufferedImage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.*

object WebRTCHelper {

	suspend fun setLocalDescription(peerConnection: RTCPeerConnection, description: RTCSessionDescription) {
		suspendCancellableCoroutine { cont ->
			peerConnection.setLocalDescription(
				description,
				object : SetSessionDescriptionObserver {
					override fun onSuccess() {
						cont.resume(Unit)
					}

					override fun onFailure(error: String) {
						cont.resumeWithException(IllegalStateException("Failed to set local description: $error"))
					}
				},
			)
		}
	}

	suspend fun setRemoteDescription(peerConnection: RTCPeerConnection, description: RTCSessionDescription) {
		suspendCancellableCoroutine { cont ->
			peerConnection.setRemoteDescription(
				description,
				object : SetSessionDescriptionObserver {
					override fun onSuccess() {
						cont.resume(Unit)
					}

					override fun onFailure(error: String) {
						cont.resumeWithException(IllegalStateException("Failed to set remote description: $error"))
					}
				},
			)
		}
	}

	suspend fun createOffer(peerConnection: RTCPeerConnection, options: RTCOfferOptions): RTCSessionDescription = suspendCancellableCoroutine { cont ->
		peerConnection.createOffer(
			options,
			object : CreateSessionDescriptionObserver {
				override fun onSuccess(description: RTCSessionDescription) {
					cont.resume(description)
				}

				override fun onFailure(error: String) {
					cont.resumeWithException(IllegalStateException("Failed to create offer SDP: $error"))
				}
			},
		)
	}

	suspend fun createAnswer(peerConnection: RTCPeerConnection): RTCSessionDescription = suspendCancellableCoroutine { cont ->
		peerConnection.createAnswer(
			RTCAnswerOptions(),
			object : CreateSessionDescriptionObserver {
				override fun onSuccess(description: RTCSessionDescription) {
					cont.resume(description)
				}

				override fun onFailure(error: String) {
					cont.resumeWithException(IllegalStateException("Failed to create answer: $error"))
				}
			},
		)
	}

	fun convertVideoFrameToBufferedImage(frame: VideoFrame): BufferedImage {
		val buffer = frame.buffer

		val width = buffer.width
		val height = buffer.height

		val rgba = ByteArray(width * height * 4)
		VideoBufferConverter.convertFromI420(buffer, rgba, FourCC.RGBA)

		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

		var si = 0

		for (y in 0 until height) {
			for (x in 0 until width) {
				// NOTE: FourCC.RGBA is actually ABGR in this pipeline
				val a = rgba[si++]
				val b = rgba[si++]
				val g = rgba[si++]
				val r = rgba[si++]

				val rgb = (r.toInt() and 0xFF shl 16) or
					(g.toInt() and 0xFF shl 8) or
					(b.toInt() and 0xFF)

				image.setRGB(x, y, rgb)
			}
		}

		if (frame.rotation != 0) {
			val rotatedImage = BufferedImage(height, width, BufferedImage.TYPE_INT_RGB)
			val g = rotatedImage.createGraphics()
			when (frame.rotation) {
				90 -> {
					g.rotate(PI * 0.5)
					g.translate(0.0, -height.toDouble())
				}

				180 -> {
					g.rotate(PI)
					g.translate(-width.toDouble(), -height.toDouble())
				}

				270 -> {
					g.rotate(PI * 1.5)
					g.translate(-width.toDouble(), 0.0)
				}

				else -> {
					error("Unsupported rotation")
				}
			}
			g.drawImage(image, 0, 0, null)
			g.dispose()

			return rotatedImage
		}

		return image
	}

	fun convertBufferedImageToVideoFrameBuffer(image: BufferedImage): VideoFrameBuffer {
		require(image.type == BufferedImage.TYPE_INT_RGB) {
			"Expected TYPE_INT_RGB, got ${image.type}"
		}

		val width = image.width
		val height = image.height

		val rgba = ByteArray(width * height * 4)

		var di = 0

		for (y in 0 until height) {
			for (x in 0 until width) {
				val rgb = image.getRGB(x, y)

				val r = (rgb shr 16) and 0xFF
				val g = (rgb shr 8) and 0xFF
				val b = rgb and 0xFF

				// NOTE: converter expects ABGR order for FourCC.RGBA
				rgba[di++] = 0xFF.toByte() // A
				rgba[di++] = b.toByte() // B
				rgba[di++] = g.toByte() // G
				rgba[di++] = r.toByte() // R
			}
		}

		val i420 = NativeI420Buffer.allocate(width, height)
		VideoBufferConverter.convertToI420(rgba, i420, FourCC.RGBA)

		return i420
	}
}
