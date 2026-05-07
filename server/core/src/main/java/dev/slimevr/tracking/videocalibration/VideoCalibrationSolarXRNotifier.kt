package dev.slimevr.tracking.videocalibration

import com.google.flatbuffers.FlatBufferBuilder
import dev.slimevr.protocol.GenericConnection
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.trackers.AssignedTrackers
import dev.slimevr.tracking.videocalibration.vision.Camera
import solarxr_protocol.MessageBundle
import solarxr_protocol.datatypes.TransactionId
import solarxr_protocol.datatypes.math.Quat
import solarxr_protocol.datatypes.math.Vec3f
import solarxr_protocol.rpc.RpcMessage
import solarxr_protocol.rpc.RpcMessageHeader
import solarxr_protocol.rpc.VideoCalibrationCamera
import solarxr_protocol.rpc.VideoCalibrationError
import solarxr_protocol.rpc.VideoCalibrationForwardAndLeaningForwardNotAligned
import solarxr_protocol.rpc.VideoCalibrationMissingTrackers
import solarxr_protocol.rpc.VideoCalibrationProgress
import solarxr_protocol.rpc.VideoCalibrationStatus
import solarxr_protocol.rpc.VideoCalibrationTrackerStatus

class VideoCalibrationSolarXRNotifier(
	private val connection: GenericConnection,
) : VideoCalibrationObserver {

	override fun onConnectingToServer() {
		sendStatus(VideoCalibrationStatus.CONNECTING_TO_SERVER)
	}

	override fun onConnectingToWebcam() {
		sendStatus(VideoCalibrationStatus.CONNECTING_TO_WEBCAM)
	}

	override fun onWaitingForUserStart() {
		sendStatus(VideoCalibrationStatus.WAITING_FOR_USER_START)
	}

	override fun onSolvingCameraExtrinsic() {
		sendStatus(VideoCalibrationStatus.SOLVING_CAMERA_EXTRINSIC)
	}

	override fun onCapturingForwardPose() {
		sendStatus(VideoCalibrationStatus.CAPTURING_FORWARD_POSE)
	}

	override fun onCapturingLeaningForwardPose() {
		sendStatus(VideoCalibrationStatus.CAPTURING_LEANING_FORWARD_POSE)
	}

	override fun onAligningUpperBodyTrackers() {
		sendStatus(VideoCalibrationStatus.ALIGNING_UPPER_BODY_TRACKERS)
	}

	override fun onAligningRemainingTrackers() {
		sendStatus(VideoCalibrationStatus.ALIGNING_REMAINING_TRACKERS)
	}

	override fun onOptimizingBodyProportions() {
		sendStatus(VideoCalibrationStatus.OPTIMIZING_BODY_PROPORTIONS)
	}

	override fun onComplete() {
		sendStatus(VideoCalibrationStatus.COMPLETE)
	}

	override fun onCameraExtrinsic(camera: Camera) {
		sendCamera(camera)
	}

	override fun onTrackersAligned(trackers: AssignedTrackers, aligned: Set<TrackerPosition>) {
		val trackerStatuses =
			trackers.trackers.map {
				it.trackerPosition!! to aligned.contains(it.trackerPosition)
			}

		sendTrackers(trackerStatuses)
	}

	override fun onBodyProportions(bodyProportions: Map<SkeletonConfigOffsets, Pair<Float, Float>>) {
		// TODO
	}

	override fun onMissingPositionalTrackersError(trackers: List<TrackerPosition>) {
		val fbb = FlatBufferBuilder(128)

		//
		// Convert to BodyPart byte array
		//
		val bodyParts = trackers
			.map { it.bodyPart.toByte() }
			.toByteArray()

		val missingVector = VideoCalibrationMissingTrackers
			.createMissingTrackersVector(fbb, bodyParts)

		VideoCalibrationMissingTrackers.startVideoCalibrationMissingTrackers(fbb)
		VideoCalibrationMissingTrackers.addMissingTrackers(fbb, missingVector)
		val missingOffset = VideoCalibrationMissingTrackers.endVideoCalibrationMissingTrackers(fbb)

		//
		// Error wrapper
		//
		VideoCalibrationError.startVideoCalibrationError(fbb)
		VideoCalibrationError.addMissingPositionalTrackers(fbb, missingOffset)
		val errorOffset = VideoCalibrationError.endVideoCalibrationError(fbb)

		val messageOffset = createRPCMessage(
			fbb,
			RpcMessage.VideoCalibrationError,
			errorOffset,
			null,
		)

		fbb.finish(messageOffset)
		connection.send(fbb.dataBuffer())
	}

	override fun onMissingRequiredIMUTrackersError(trackers: List<TrackerPosition>) {
		val fbb = FlatBufferBuilder(128)

		val bodyParts = trackers
			.map { it.bodyPart.toByte() }
			.toByteArray()

		val missingVector = VideoCalibrationMissingTrackers
			.createMissingTrackersVector(fbb, bodyParts)

		VideoCalibrationMissingTrackers.startVideoCalibrationMissingTrackers(fbb)
		VideoCalibrationMissingTrackers.addMissingTrackers(fbb, missingVector)
		val missingOffset = VideoCalibrationMissingTrackers.endVideoCalibrationMissingTrackers(fbb)

		VideoCalibrationError.startVideoCalibrationError(fbb)
		VideoCalibrationError.addMissingRequiredImuTrackers(fbb, missingOffset)
		val errorOffset = VideoCalibrationError.endVideoCalibrationError(fbb)

		val messageOffset = createRPCMessage(
			fbb,
			RpcMessage.VideoCalibrationError,
			errorOffset,
			null,
		)

		fbb.finish(messageOffset)
		connection.send(fbb.dataBuffer())
	}

	override fun onForwardAndLeaningForwardNotAligned(yawDifference: Double) {
		val fbb = FlatBufferBuilder(64)

		VideoCalibrationForwardAndLeaningForwardNotAligned
			.startVideoCalibrationForwardAndLeaningForwardNotAligned(fbb)
		VideoCalibrationForwardAndLeaningForwardNotAligned
			.addYawDifference(fbb, yawDifference.toFloat())
		val forwardOffset =
			VideoCalibrationForwardAndLeaningForwardNotAligned
				.endVideoCalibrationForwardAndLeaningForwardNotAligned(fbb)

		VideoCalibrationError.startVideoCalibrationError(fbb)
		VideoCalibrationError.addForwardAndLeaningForwardNotAligned(fbb, forwardOffset)
		val errorOffset = VideoCalibrationError.endVideoCalibrationError(fbb)

		val messageOffset = createRPCMessage(
			fbb,
			RpcMessage.VideoCalibrationError,
			errorOffset,
			null,
		)

		fbb.finish(messageOffset)
		connection.send(fbb.dataBuffer())
	}

	// TODO: Review AI written code
	private fun sendStatus(status: Int) {
		val fbb = FlatBufferBuilder(128)

		VideoCalibrationProgress.startVideoCalibrationProgress(fbb)
		VideoCalibrationProgress.addStatus(fbb, status)
		val progressOffset = VideoCalibrationProgress.endVideoCalibrationProgress(fbb)

		val messageOffset = createRPCMessage(
			fbb,
			RpcMessage.VideoCalibrationProgress,
			progressOffset,
			null,
		)

		fbb.finish(messageOffset)
		connection.send(fbb.dataBuffer())
	}

	private fun sendCamera(camera: Camera) {
		val fbb = FlatBufferBuilder(256)

		val q = camera.extrinsic.worldToCamera
		val t = camera.extrinsic.worldOriginInCamera
		val intr = camera.intrinsic

		VideoCalibrationCamera.startVideoCalibrationCamera(fbb)

		// Struct MUST be created immediately before add
		val quat = Quat.createQuat(
			fbb,
			q.x.toFloat(),
			q.y.toFloat(),
			q.z.toFloat(),
			q.w.toFloat(),
		)
		VideoCalibrationCamera.addWorldToCamera(fbb, quat)

		val vec = Vec3f.createVec3f(
			fbb,
			t.x.toFloat(),
			t.y.toFloat(),
			t.z.toFloat(),
		)
		VideoCalibrationCamera.addWorldOriginInCamera(fbb, vec)

		VideoCalibrationCamera.addFx(fbb, intr.fx.toFloat())
		VideoCalibrationCamera.addFy(fbb, intr.fy.toFloat())
		VideoCalibrationCamera.addTx(fbb, intr.tx.toFloat())
		VideoCalibrationCamera.addTy(fbb, intr.ty.toFloat())
		VideoCalibrationCamera.addWidth(fbb, camera.imageSize.width)
		VideoCalibrationCamera.addHeight(fbb, camera.imageSize.height)

		val cameraOffset = VideoCalibrationCamera.endVideoCalibrationCamera(fbb)

		VideoCalibrationProgress.startVideoCalibrationProgress(fbb)
		VideoCalibrationProgress.addCamera(fbb, cameraOffset)
		val progressOffset = VideoCalibrationProgress.endVideoCalibrationProgress(fbb)

		val messageOffset = createRPCMessage(
			fbb,
			RpcMessage.VideoCalibrationProgress,
			progressOffset,
			null,
		)

		fbb.finish(messageOffset)
		connection.send(fbb.dataBuffer())
	}

	private fun sendTrackers(trackers: List<Pair<TrackerPosition, Boolean>>) {
		val fbb = FlatBufferBuilder(256)

		//
		// Build tracker status table offsets
		//
		val trackerOffsets = IntArray(trackers.size)

		for (i in trackers.indices.reversed()) {
			val (pos, aligned) = trackers[i]

			val bodyPart = pos.bodyPart

			VideoCalibrationTrackerStatus.startVideoCalibrationTrackerStatus(fbb)
			VideoCalibrationTrackerStatus.addBodyPart(fbb, bodyPart)
			VideoCalibrationTrackerStatus.addAligned(fbb, aligned)
			trackerOffsets[i] = VideoCalibrationTrackerStatus.endVideoCalibrationTrackerStatus(fbb)
		}

		val trackersVector = VideoCalibrationProgress.createTrackersVector(fbb, trackerOffsets)

		//
		// Build progress message
		//
		VideoCalibrationProgress.startVideoCalibrationProgress(fbb)
		VideoCalibrationProgress.addTrackers(fbb, trackersVector)
		val progressOffset = VideoCalibrationProgress.endVideoCalibrationProgress(fbb)

		val messageOffset = createRPCMessage(
			fbb,
			RpcMessage.VideoCalibrationProgress,
			progressOffset,
			null,
		)

		fbb.finish(messageOffset)
		connection.send(fbb.dataBuffer())
	}

	fun createRPCMessage(fbb: FlatBufferBuilder, messageType: Byte, messageOffset: Int, respondTo: RpcMessageHeader? = null): Int {
		val data = IntArray(1)

		RpcMessageHeader.startRpcMessageHeader(fbb)
		RpcMessageHeader.addMessage(fbb, messageOffset)
		RpcMessageHeader.addMessageType(fbb, messageType)
		respondTo?.txId()?.let { txId ->
			RpcMessageHeader.addTxId(fbb, TransactionId.createTransactionId(fbb, txId.id()))
		}
		data[0] = RpcMessageHeader.endRpcMessageHeader(fbb)

		val messages = MessageBundle.createRpcMsgsVector(fbb, data)

		MessageBundle.startMessageBundle(fbb)
		MessageBundle.addRpcMsgs(fbb, messages)
		return MessageBundle.endMessageBundle(fbb)
	}
}
