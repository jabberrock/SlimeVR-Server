package dev.slimevr.tracking.videocalibration.vision

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.slimevr.tracking.videocalibration.mdns.MDNSEndpoint
import dev.slimevr.tracking.videocalibration.util.OnlineService
import dev.slimevr.tracking.videocalibration.util.WebRTCHelper
import io.eiren.util.logging.Logger
import io.github.axisangles.ktmath.QuaternionD
import io.github.axisangles.ktmath.Vector3D
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Dimension
import kotlin.math.*
import kotlin.time.TimeSource

/**
 * Connects to the Simple Webcam app for video and vision data.
 */
class SimpleWebcam(
	val endpoint: MDNSEndpoint,
	val incomingVideo: Channel<VideoImage>,
	val incomingCameraData: Channel<Camera>,
) : OnlineService {

	private val logger = Logger(this::class.simpleName)

	private val factory: PeerConnectionFactory
	private val observer: PeerConnectionHandler
	private val connection: RTCPeerConnection

	private val iceGatheringComplete = CompletableDeferred<Unit>()

	private val _connected = CompletableDeferred<Unit>()
	private val _completed = CompletableDeferred<Unit>()

	override val connected: Deferred<Unit> = _connected
	override val completed: Deferred<Unit> = _completed

	private val client = HttpClient(CIO) {
		install(ContentNegotiation) {
			json()
		}
	}

	init {
		factory = PeerConnectionFactory()
		observer = PeerConnectionHandler(this)
		connection = factory.createPeerConnection(RTCConfiguration(), observer)

		val track = factory.createVideoTrack(VIDEO_TRACK, CustomVideoSource())
		connection.addTrack(track, listOf(STREAM_ID))

		val visionDataChannel = connection.createDataChannel(VISION_DATA_CHANNEL, RTCDataChannelInit())
		visionDataChannel.registerObserver(DataChannelHandler(this, visionDataChannel, true))
	}

	override suspend fun run() = coroutineScope {
		try {
			connect()
			connected.await()
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
		logger.debug("Connecting to WebRTC peer...")

		logger.debug("Creating offer...")
		val offer = WebRTCHelper.createOffer(connection, RTCOfferOptions())

		logger.debug("Setting local description...")
		WebRTCHelper.setLocalDescription(connection, offer)

		logger.debug("Waiting for ICE gathering to complete...")
		iceGatheringComplete.await()

		logger.debug("Requesting answer SDP...")
		val answerSDP = sendOffer(connection.localDescription.sdp)

		logger.debug("Setting remote description...")
		WebRTCHelper.setRemoteDescription(connection, RTCSessionDescription(RTCSdpType.ANSWER, answerSDP))
	}

	private suspend fun sendOffer(offerSDP: String): String {
		val urlBuilder = URLBuilder(
			protocol = URLProtocol.Companion.HTTP,
			host = endpoint.inetAddress.hostAddress,
			port = endpoint.port,
			pathSegments = listOf("connect"),
		)

		val url = urlBuilder.build()
		logger.debug("Sending offer SDP to: $url")

		val offerResponse = client.post(url) {
			contentType(ContentType.Application.Json)
			setBody(ConnectRequestJSON(offerSDP))
		}

		if (offerResponse.status != HttpStatusCode.OK) {
			error("Failed to get response from signaling server: ${offerResponse.status}")
		}

		val answer = offerResponse.body<ConnectResponseJSON>()

		return answer.answerSdp
	}

	@Serializable
	private class ConnectRequestJSON(
		val offerSdp: String,
	)

	@Serializable
	private class ConnectResponseJSON(
		val answerSdp: String,
	)

	private fun processCameraData(buffer: RTCDataChannelBuffer) {
		if (buffer.binary) {
			return
		}

		val visionJSON: VisionJSON
		try {
			visionJSON = Json.Default.decodeFromString<VisionJSON>(Charsets.UTF_8.decode(buffer.data).toString())
		} catch (e: Exception) {
			logger.warning("Failed to parse vision data from data channel", e)
			return
		}

		val deviceToArbitraryZVertical =
			visionJSON.deviceToArbitraryZVertical.let {
				QuaternionD(
					it.w,
					it.x,
					it.y,
					it.z,
				)
			}

		val cameraToArbitraryYVertical =
			QuaternionD.rotationAroundXAxis(-PI * 0.5) *
				deviceToArbitraryZVertical *
				QuaternionD.rotationAroundZAxis(PI)

		val camera =
			Camera(
				Camera.Extrinsic.fromCameraPose(
					cameraToArbitraryYVertical,
					Vector3D.NULL,
				),
				visionJSON.cameraIntrinsic.let {
					Camera.Intrinsic(
						it.fx,
						it.fy,
						it.tx,
						it.ty,
					)
				},
				visionJSON.cameraIntrinsic.let { Dimension(it.width, it.height) },
			)

		incomingCameraData.trySend(camera)
	}

	@Serializable
	private data class VisionJSON(
		val cameraIntrinsic: CameraIntrinsicJSON,
		val deviceToArbitraryZVertical: QuaternionJSON,
	)

	@Serializable
	private data class CameraIntrinsicJSON(
		val fx: Double,
		val fy: Double,
		val tx: Double,
		val ty: Double,
		val width: Int,
		val height: Int,
	)

	@Serializable
	private data class QuaternionJSON(
		val w: Double,
		val x: Double,
		val y: Double,
		val z: Double,
	)

	private fun cancel(cause: CancellationException) {
		_connected.cancel(cause)
		_completed.cancel(cause)
	}

	private fun close(cause: Throwable? = null) {
		if (cause != null) {
			_connected.completeExceptionally(cause)
			_completed.completeExceptionally(cause)
		} else {
			_connected.complete(Unit)
			_completed.complete(Unit)
		}
	}

	class PeerConnectionHandler(
		private val self: SimpleWebcam,
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
			val track = receiver.track
			if (track is VideoTrack) {
				track.addSink {
					val now = TimeSource.Monotonic.markNow()
					val image = WebRTCHelper.convertVideoFrameToBufferedImage(it)
					self.incomingVideo.trySend(VideoImage(now, image))
				}
			}
		}

		override fun onDataChannel(dataChannel: RTCDataChannel) {
			// Do nothing
		}
	}

	class DataChannelHandler(
		private val self: SimpleWebcam,
		private val dataChannel: RTCDataChannel,
		private val isVision: Boolean,
	) : RTCDataChannelObserver {

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
			if (isVision) {
				self.processCameraData(buffer)
			}
		}
	}

	companion object {
		private const val VIDEO_TRACK = "video"
		private const val STREAM_ID = "stream0"
		private const val VISION_DATA_CHANNEL = "vision"
	}
}
