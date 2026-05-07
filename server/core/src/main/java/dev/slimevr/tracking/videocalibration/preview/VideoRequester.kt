package dev.slimevr.tracking.videocalibration.preview

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.slimevr.tracking.videocalibration.util.WebRTCHelper
import io.eiren.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A client which is requesting for video from the SlimeVR server.
 */
class VideoRequester(
	private val offerSdp: String,
	private val sendAnswerSdp: (answerSdp: String) -> Unit,
) : VideoSink {

	private val logger = Logger(this::class.simpleName)

	private val factory: PeerConnectionFactory
	private val observer: PeerConnectionHandler
	private val connection: RTCPeerConnection
	private val videoSource: CustomVideoSource

	private val iceGatheringComplete = CompletableDeferred<Unit>()

	private val _outgoingVideo = Channel<BufferedImage>()
	private val _connected = CompletableDeferred<Unit>()
	private val _completed = CompletableDeferred<Unit>()

	override val outgoingVideo: SendChannel<BufferedImage> = _outgoingVideo
	override val connected: Deferred<Unit> = _connected
	override val completed: Deferred<Unit> = _completed

	init {
		factory = PeerConnectionFactory()
		observer = PeerConnectionHandler(this)
		connection = factory.createPeerConnection(RTCConfiguration(), observer)

		videoSource = CustomVideoSource()
		val track = factory.createVideoTrack(VIDEO_TRACK, videoSource)
		connection.addTrack(track, listOf(STREAM_ID))

		val sender = connection.senders.first()
		val senderParams = sender.parameters
		val senderParamsEncoding = senderParams.encodings.first()
		senderParamsEncoding.minBitrate = MIN_BITRATE
		senderParamsEncoding.maxBitrate = MAX_BITRATE
		sender.parameters = senderParams
	}

	override suspend fun run() = coroutineScope {
		try {
			connect()
			connected.await()
			launch { runSendVideoLoop() }
			completed.await()
		} catch (e: CancellationException) {
			logger.info("WebRTC connection cancelled")
			cancel(e)
			throw e
		} catch (e: Exception) {
			logger.info("WebRTC connection encountered exception", e)
			close(e)
			throw e
		} finally {
			connection.close()
			close()
		}
	}

	private suspend fun connect() {
		logger.debug("Accepting WebRTC connection from peer...")

		logger.debug("Setting remote description...")
		WebRTCHelper.setRemoteDescription(connection, RTCSessionDescription(RTCSdpType.OFFER, offerSdp))

		logger.debug("Creating answer...")
		val answer = WebRTCHelper.createAnswer(connection)

		logger.debug("Setting local description...")
		WebRTCHelper.setLocalDescription(connection, answer)

		logger.debug("Waiting for ICE gathering to complete...")
		iceGatheringComplete.await()

		sendAnswerSdp(connection.localDescription.sdp)
	}

	private suspend fun runSendVideoLoop() {
		val startTime = TimeSource.Monotonic.markNow()
		for (image in _outgoingVideo) {
			val now = TimeSource.Monotonic.markNow()
			val timestampNs = (now - startTime).inWholeNanoseconds
			val frame = VideoFrame(WebRTCHelper.convertBufferedImageToVideoFrameBuffer(image), timestampNs)
			videoSource.pushFrame(frame)
		}
	}

	private fun cancel(cause: CancellationException) {
		_outgoingVideo.cancel(cause)
		_connected.cancel(cause)
		_completed.cancel(cause)
	}

	private fun close(cause: Throwable? = null) {
		_outgoingVideo.close(cause)
		if (cause != null) {
			_connected.completeExceptionally(cause)
			_completed.completeExceptionally(cause)
		} else {
			_connected.complete(Unit)
			_completed.complete(Unit)
		}
	}

	class PeerConnectionHandler(
		private val self: VideoRequester,
	) : PeerConnectionObserver {

		override fun onIceCandidate(candidate: RTCIceCandidate) {
			// Do nothing
		}

		override fun onIceGatheringChange(state: RTCIceGatheringState) {
			if (state == RTCIceGatheringState.COMPLETE) {
				self.iceGatheringComplete.complete(Unit)
			}
		}

		override fun onConnectionChange(state: RTCPeerConnectionState) {
			when (state) {
				RTCPeerConnectionState.NEW -> {
					// Do nothing
				}

				RTCPeerConnectionState.CONNECTING -> {
					self.logger.info("WebRTC connecting...")
				}

				RTCPeerConnectionState.CONNECTED -> {
					self.logger.info("WebRTC peer connected")
					self._connected.complete(Unit)
				}

				RTCPeerConnectionState.DISCONNECTED -> {
					self.logger.info("WebRTC peer disconnected")
				}

				RTCPeerConnectionState.FAILED -> {
					self.logger.info("WebRTC connection failed")

					// We can't recover from a failed state because we have
					// no mechanism send new ICE candidates to the peer. So
					// we just close the connection.
					self.close(IllegalStateException("WebRTC connection failed"))
					self.connection.close()
				}

				RTCPeerConnectionState.CLOSED -> {
					self.logger.info("WebRTC closed")
					self.close()
				}
			}
		}

		override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<out MediaStream?>) {
			// Do nothing
		}

		override fun onDataChannel(dataChannel: RTCDataChannel) {
			self.logger.debug("Data channel ${dataChannel.label} connected")

			// Accept any data channel and use it as a keepalive
			dataChannel.registerObserver(object : RTCDataChannelObserver {
				override fun onBufferedAmountChange(previousAmount: Long) {
					// Do nothing
				}

				override fun onStateChange() {
					if (dataChannel.state == RTCDataChannelState.CLOSED) {
						self.close(IllegalStateException("Data channel closed by peer"))
						self.connection.close()
					}
				}

				override fun onMessage(buffer: RTCDataChannelBuffer) {
					// Do nothing
				}
			})
		}
	}

	companion object {
		private const val VIDEO_TRACK = "video"
		private const val STREAM_ID = "stream0"
		private const val MIN_BITRATE = 2_000_000
		private const val MAX_BITRATE = 4_000_000
	}
}
