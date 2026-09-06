package dev.slimevr.tracking.videocalibration

import dev.slimevr.VRServer
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.human.HumanPoseEstimator
import dev.slimevr.tracking.videocalibration.human.HumanPoseSnapshot
import dev.slimevr.tracking.videocalibration.preview.PreviewGenerator
import dev.slimevr.tracking.videocalibration.preview.VideoSink
import dev.slimevr.tracking.videocalibration.trackers.AssignedTrackers
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshotter
import dev.slimevr.tracking.videocalibration.util.DebugOutput
import dev.slimevr.tracking.videocalibration.util.OnlineService
import dev.slimevr.tracking.videocalibration.vision.Camera
import dev.slimevr.tracking.videocalibration.vision.VideoImage
import io.eiren.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class VideoCalibration(
	private val server: VRServer,
	private val requester: VideoSink,
	private val visionSourceFactory: (incomingVideo: Channel<VideoImage>, incomingCameraData: Channel<Camera>) -> OnlineService,
	private val humanPoseEstimatorFactory: () -> HumanPoseEstimator,
	private val initialHmdHeight: Float,
	private val initialSkeletonOffsets: Map<SkeletonConfigOffsets, Float>,
	private val observer: VideoCalibrationObserver,
	private val debugOutput: DebugOutput = DebugOutput(DebugOutput.DEFAULT_DIR),
) {
	private val logger = Logger(this::class.simpleName)

	private val database = Database()

	private val previewGenerator = PreviewGenerator()

	private data class StartCalibrationRequest(
		val selectedProcess: VideoCalibrationProcess.Process,
		val trackers: AssignedTrackers,
	)

	private val onStartCalibration = Channel<StartCalibrationRequest>(Channel.RENDEZVOUS)

	private class RestartException : Exception("Restart")

	/**
	 * Runs the video calibration service.
	 */
	suspend fun run() = coroutineScope {
		launch {
			expectRunForever("Requester") {
				observer.onConnectingToServer()
				requester.run()
			}
		}
		requester.connected.await()

		retryOnFailure("Vision source", RETRY_INTERVAL) {
			coroutineScope {
				val incomingVideo = Channel<VideoImage>(Channel.CONFLATED)
				val incomingCameraData = Channel<Camera>(Channel.CONFLATED)

				val visionSource = visionSourceFactory(incomingVideo, incomingCameraData)
				launch {
					expectRunForever("Vision source") {
						observer.onConnectingToWebcam()
						visionSource.run()
					}
				}
				visionSource.connected.await()

				val camera = AtomicReference(incomingCameraData.receive())
				launch {
					expectRunForever("Pose detection") {
						runPoseEstimators(
							incomingVideo,
							camera,
							requester.outgoingVideo,
							database,
						)
					}
				}

// 				launch {
// 					expectRunForever("Trackers snapshotter") {
// 						val trackersSnapshotter =
// 							TrackersSnapshotter(
// 								server,
// 								TRACKERS_SNAPSHOT_INTERVAL,
// 								AssignedTrackers.fromAllTrackers(server.allTrackers),
// 								database
// 							)
// 						trackersSnapshotter.run()
// 					}
// 				}

				retryOnFailure("Video calibration process", RETRY_INTERVAL) {
					observer.onWaitingForUserStart()

					val startCalibrationRequest = onStartCalibration.receive()
					logger.info("User started calibration")

					// Update the camera in case the user has moved the camera
					camera.set(incomingCameraData.receive())

					database.clear()

					coroutineScope {
						launch {
							expectRunForever("Trackers snapshotter") {
								val trackersSnapshotter =
									TrackersSnapshotter(
										server,
										TRACKERS_SNAPSHOT_INTERVAL,
										startCalibrationRequest.trackers,
										database,
									)
								trackersSnapshotter.run()
							}
						}
						launch {
							logger.info("Video calibration process starting...")
							val process =
								VideoCalibrationProcess(
									server,
									startCalibrationRequest.selectedProcess,
									startCalibrationRequest.trackers,
									database,
									initialHmdHeight,
									initialSkeletonOffsets,
									previewGenerator,
									observer,
									debugOutput,
								)
							process.run()

							logger.info("Video calibration process completed successfully")

							// We're done, but we want to restart this scope so that the
							// user can retry video calibration
							throw RestartException()
						}
					}
				}
			}
		}
	}

	private suspend fun runPoseEstimators(
		incomingVideo: ReceiveChannel<VideoImage>,
		camera: AtomicReference<Camera>,
		outgoingVideo: SendChannel<BufferedImage>,
		database: Database,
	) = coroutineScope {
		val startTime = TimeSource.Monotonic.markNow()
		repeat(NUM_PARALLEL_HUMAN_POSE_ESTIMATORS) { i ->
			launch {
				val humanPoseEstimator = humanPoseEstimatorFactory()
				for (videoImage in incomingVideo) {
					val humanPoseSnapshot =
						try {
							val joints = humanPoseEstimator.estimate(videoImage.image)
							HumanPoseSnapshot(videoImage.timestamp, joints, camera.get())
						} catch (e: Exception) {
							logger.warning("Failed to estimate human pose", e)
							continue // Skip the bad frame, keep the loop alive
						}

					database.addHumanPoseSnapshot(humanPoseSnapshot)

					previewGenerator.draw(
						videoImage.image,
						humanPoseSnapshot,
						database.getRecentTrackerSnapshots(1).firstOrNull(),
					)

					outgoingVideo.trySend(videoImage.image)
					debugOutput.saveWebcamImage(videoImage.timestamp - startTime, videoImage.image)
				}
			}
		}
	}

	private suspend inline fun expectRunForever(
		name: String,
		crossinline block: suspend () -> Unit,
	) {
		logger.info("$name starting...")
		try {
			block()
		} catch (e: CancellationException) {
			logger.info("$name cancelled")
			throw e
		} catch (e: Exception) {
			logger.info("$name encountered exception", e)
			throw e
		}

		logger.info("$name completed unexpectedly")
		error("$name completed unexpectedly")
	}

	private suspend inline fun retryOnFailure(
		name: String,
		delayBetweenRetries: Duration,
		crossinline block: suspend () -> Unit,
	) {
		while (true) {
			try {
				block()
			} catch (e: CancellationException) {
				logger.info("$name cancelled")
				throw e
			} catch (e: RestartException) {
				logger.info("$name restarting...")
				// Swallow exception so that we retry
			} catch (e: Exception) {
				logger.warning("$name encountered exception, retrying", e)
				// Swallow exception so that we retry
			}

			delay(delayBetweenRetries)
		}
	}

	/**
	 * Starts video calibration. The services must be running.
	 */
	fun startCalibration(selectedProcess: VideoCalibrationProcess.Process) {
		val trackers: AssignedTrackers
		try {
			trackers = AssignedTrackers.fromAllTrackers(server.allTrackers)
		} catch (e: Exception) {
			logger.warning("Failed to create assigned trackers", e)

			// TODO: Send actual error
			observer.onMissingRequiredIMUTrackersError(
				listOf(
					TrackerPosition.HEAD,
					TrackerPosition.LEFT_HAND,
					TrackerPosition.RIGHT_HAND,
				),
			)

			return
		}

		onStartCalibration.trySend(StartCalibrationRequest(selectedProcess, trackers))
	}

	companion object {
		private val TRACKERS_SNAPSHOT_INTERVAL = 1.seconds / 120
		private val RETRY_INTERVAL = 1.seconds
		private const val NUM_PARALLEL_HUMAN_POSE_ESTIMATORS = 2
	}
}
