package dev.slimevr.tracking.videocalibration.sources

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCRtpTransceiverDirection
import dev.onvoid.webrtc.RTCRtpTransceiverInit
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.slimevr.tracking.videocalibration.data.Camera
import dev.slimevr.tracking.videocalibration.data.CameraExtrinsic
import dev.slimevr.tracking.videocalibration.data.CameraIntrinsic
import dev.slimevr.tracking.videocalibration.snapshots.ImageSnapshot
import dev.slimevr.tracking.videocalibration.util.DebugOutput
import io.eiren.util.logging.LogManager
import io.github.axisangles.ktmath.QuaternionD
import io.github.axisangles.ktmath.Vector3D
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.awt.Color
import java.awt.Dimension
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage
import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.awt.image.Raster
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class WebRTCVideoSource(
	private val webcamService: MDNSRegistry.Service,
	private val debugOutput: DebugOutput,
) {

	enum class Status {
		NOT_STARTED,
		INITIALIZING,
		RUNNING,
		DONE,
	}

	val status = AtomicReference(Status.NOT_STARTED)
	val imageSnapshots = Channel<ImageSnapshot>(Channel.Factory.CONFLATED)

	private val scope = CoroutineScope(Dispatchers.IO)

	private var initialCamera: Camera? = null
	private var peerConnection: RTCPeerConnection? = null

	fun start() {
		scope.launch {
			try {
				connect()
			} catch (e: Exception) {
				LogManager.warning("WebRTC video source failed", e)
				requestStop()
			}
		}
	}

	fun requestStop() {
		scope.cancel()
		peerConnection?.close()
		status.set(Status.DONE)
	}

	private suspend fun connect() {
		status.set(Status.INITIALIZING)

		val startTime = TimeSource.Monotonic.markNow()

		val factory = PeerConnectionFactory()
		val config = RTCConfiguration()
		config.iceServers.clear()

		val peerConnection = factory.createPeerConnection(
			config,
			object : PeerConnectionObserver {

				override fun onIceCandidate(candidate: RTCIceCandidate) {
					// Do nothing
				}

				override fun onTrack(transceiver: RTCRtpTransceiver) {
					val track = transceiver.receiver.track
					if (track is VideoTrack) {
						track.addSink { frame ->
							val initialCamera = initialCamera
							if (initialCamera == null) {
								return@addSink
							}

							val buffer = frame.buffer.toI420()
							if (buffer.width != initialCamera.imageSize.width || buffer.height != initialCamera.imageSize.height) {
								return@addSink
							}

							val timeOffset = TimeSource.Monotonic.markNow() - startTime

							val rgbaBuffer = ByteArray(buffer.width * buffer.height * 4)
							VideoBufferConverter.convertFromI420(buffer, rgbaBuffer, FourCC.RGBA)

							val raster = Raster.createInterleavedRaster(
								DataBufferByte(rgbaBuffer, rgbaBuffer.size),
								buffer.width,
								buffer.height,
								4 * buffer.width,
								4,
								intArrayOf(3, 2, 1, 0),
								null,
							)

							val colorModel = ComponentColorModel(
								ColorSpace.getInstance(ColorSpace.CS_sRGB),
								true,
								false,
								Transparency.TRANSLUCENT,
								DataBuffer.TYPE_BYTE,
							)

							val image = BufferedImage(colorModel, raster, false, null)

//							val image = argbToBufferedImageFast(rgbaBuffer, buffer.width, buffer.height)
//							debugOutput.saveWebcamImage(timeOffset, image)

//							val image = BufferedImage(buffer.width, buffer.height,
//								BufferedImage.TYPE_INT_RGB)
//							val g1 = image.createGraphics()
//							g1.background = Color.RED
//							g1.clearRect(0, 0, image.width, image.height)
//							g1.dispose()

//							debugOutput.saveWebcamImage(timeOffset, image)

							val flippedImage = BufferedImage(image.width, image.height,
								BufferedImage.TYPE_INT_RGB)
							val g = flippedImage.createGraphics()
							g.drawImage(image, 0, 0, image.width, image.height, image.width, 0, 0, image.height, null)
							g.dispose()

							debugOutput.saveWebcamImage(timeOffset, flippedImage)

							val imageSnapshot = ImageSnapshot(TimeSource.Monotonic.markNow(), timeOffset, flippedImage, initialCamera)
							imageSnapshots.trySend(imageSnapshot)
						}
					}
				}

				override fun onSignalingChange(state: RTCSignalingState) {
					if (state == RTCSignalingState.CLOSED) {
						requestStop()
					}
				}
			},
		)

		val videoSource = CustomVideoSource()
		val videoTrack = factory.createVideoTrack("video0", videoSource)
		val transceiverInit = RTCRtpTransceiverInit()
		transceiverInit.direction = RTCRtpTransceiverDirection.RECV_ONLY
		peerConnection.addTransceiver(videoTrack, transceiverInit)

		val options = RTCOfferOptions()
		val offer = suspendCoroutine { cont ->
			peerConnection.createOffer(
				options,
				object : CreateSessionDescriptionObserver {
					override fun onSuccess(description: RTCSessionDescription?) {
						cont.resume(description)
					}

					override fun onFailure(error: String?) {
						cont.resumeWithException(IllegalStateException(error))
					}
				},
			)
		}

		if (offer == null) {
			LogManager.warning("DONE to create WebRTC offer")
			status.set(Status.DONE)
			return
		}

		suspendCoroutine { cont ->
			peerConnection.setLocalDescription(
				offer,
				object : SetSessionDescriptionObserver {
					override fun onSuccess() {
						cont.resume(Unit)
					}

					override fun onFailure(error: String?) {
						cont.resumeWithException(IllegalStateException(error))
					}
				},
			)
		}

		while (peerConnection.iceGatheringState != RTCIceGatheringState.COMPLETE) {
			delay(1.seconds)
		}

		val client = HttpClient(CIO) {
			install(Logging)
			install(ContentNegotiation) {
				json()
			}
		}

		val urlBuilder = URLBuilder(
			protocol = URLProtocol.HTTP,
			host = webcamService.host.hostAddress,
			port = webcamService.port,
			pathSegments = listOf("offer"),
		)

		val url = urlBuilder.build()
		LogManager.info("Sending WebRTC offer to: $url")

		val offerRequest = client.preparePost(url) {
			contentType(ContentType.Application.Json)
			setBody(OfferBody(peerConnection.localDescription.sdp))
		}

		val offerResponse = offerRequest.execute()

		if (offerResponse.status != HttpStatusCode.OK) {
			LogManager.warning("DONE to get answer from WebRTC offer")
			status.set(Status.DONE)
			return
		}

		val answer = offerResponse.body<OfferResponse>()

		suspendCoroutine { cont ->
			peerConnection.setRemoteDescription(
				RTCSessionDescription(RTCSdpType.ANSWER, answer.sdp),
				object : SetSessionDescriptionObserver {
					override fun onSuccess() {
						cont.resume(Unit)
					}

					override fun onFailure(error: String?) {
						cont.resumeWithException(IllegalStateException(error))
					}
				},
			)
		}

		val camera =
			Camera(
				CameraExtrinsic.fromCameraPose(
					answer.cameraToWorld.let { QuaternionD(it[0], it[1], it[2], it[3]) },
					Vector3D.NULL,
				),
				CameraIntrinsic(
					answer.intrinsics.fx,
					answer.intrinsics.fy,
					answer.intrinsics.tx,
					answer.intrinsics.ty,
				),
				answer.imageSize.let { Dimension(it[0], it[1]) },
			)

		this.initialCamera = camera

		debugOutput.saveCamera(camera)

		this.peerConnection = peerConnection
		this.status.set(Status.RUNNING)
	}

	private fun argbToBufferedImageFast(
		data: ByteArray,
		width: Int,
		height: Int
	): BufferedImage {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val buffer = image.raster.dataBuffer as DataBufferInt
		val pixels = buffer.data

		var i = 0
		var p = 0

		while (i < data.size) {
			pixels[p++] =
				((data[i].toInt() and 0xFF) shl 24) or
					((data[i + 1].toInt() and 0xFF) shl 16) or
					((data[i + 2].toInt() and 0xFF) shl 8) or
					(data[i + 3].toInt() and 0xFF)

			i += 4
		}

		return image
	}

	@Serializable
	class OfferBody(
		val sdp: String,
	)

	@Serializable
	class IntrinsicsResponse(
		val fx: Double,
		val fy: Double,
		val tx: Double,
		val ty: Double,
	)

	@Serializable
	class OfferResponse(
		val sdp: String,
		val imageSize: List<Int>,
		val intrinsics: IntrinsicsResponse,
		val cameraToWorld: List<Double>,
	)
}
