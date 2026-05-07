package dev.slimevr.tracking.videocalibration

import dev.slimevr.VRServer
import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.preview.PreviewGenerator
import dev.slimevr.tracking.videocalibration.steps.BodyProportionsOptimizer
import dev.slimevr.tracking.videocalibration.steps.CameraExtrinsicSolver
import dev.slimevr.tracking.videocalibration.steps.ForwardPoseCapturer
import dev.slimevr.tracking.videocalibration.steps.LeaningForwardPoseCapturer
import dev.slimevr.tracking.videocalibration.steps.NonUpperBodyTrackerAligner
import dev.slimevr.tracking.videocalibration.steps.UpperBodyTrackerAligner
import dev.slimevr.tracking.videocalibration.trackers.AssignedTrackers
import dev.slimevr.tracking.videocalibration.trackers.TrackersSnapshot
import dev.slimevr.tracking.videocalibration.util.DebugOutput
import dev.slimevr.tracking.videocalibration.vision.Camera
import io.eiren.util.logging.Logger
import io.github.axisangles.ktmath.QuaternionD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class VideoCalibrationProcess(
	private val server: VRServer,
	private val selectedProcess: Process,
	private val trackers: AssignedTrackers,
	private val database: Database,
	private val initialHmdHeight: Float,
	private val initialSkeletonOffsets: Map<SkeletonConfigOffsets, Float>,
	private val previewGenerator: PreviewGenerator,
	private val observer: VideoCalibrationObserver,
	private val debugOutput: DebugOutput,
) {
	enum class Process {
		ALIGN_CAMERA_ONLY,
		ALIGN_TRACKERS,
		OPTIMIZE_BODY_PROPORTIONS,
	}

	sealed interface Step {
		suspend fun enter()
		suspend fun run()

		data class SolveCameraExtrinsic(
			val process: VideoCalibrationProcess,
		) : Step {
			override suspend fun enter() {
				process.observer.onSolvingCameraExtrinsic()
				process.database.clearRecent()
			}

			override suspend fun run() {
				val solution = CameraExtrinsicSolver(process.debugOutput).solve(process.database)

				process.observer.onCameraExtrinsic(solution.camera)
				process.previewGenerator.setCamera(solution.camera)

				if (process.selectedProcess == Process.ALIGN_CAMERA_ONLY) {
					process.nextStep = Complete(process)
				} else {
					process.nextStep = CaptureForwardPose(process, solution.camera, solution.controllerDelay)
				}
			}
		}

		data class CaptureForwardPose(
			val process: VideoCalibrationProcess,
			val camera: Camera,
			val cameraDelay: Duration,
		) : Step {
			override suspend fun enter() {
				process.observer.onCapturingForwardPose()
				delay(2.seconds)
				process.database.clearRecent()
			}

			override suspend fun run() {
				process.observer.onCapturingForwardPose()

				val solution = ForwardPoseCapturer().capture(process.database)
				if (solution != null) {
					process.nextStep = CaptureLeaningForwardPose(process, camera, cameraDelay, solution.reference, solution.trackerRotations)
				}
			}
		}

		data class CaptureLeaningForwardPose(
			val process: VideoCalibrationProcess,
			val camera: Camera,
			val cameraDelay: Duration,
			val forwardPose: QuaternionD,
			val forwardPoseTrackers: List<TrackersSnapshot>,
		) : Step {
			override suspend fun enter() {
				process.observer.onCapturingLeaningForwardPose()
				delay(2.seconds)
				process.database.clearRecent()
			}

			override suspend fun run() {
				val solution = LeaningForwardPoseCapturer().capture(process.database, forwardPoseTrackers)
				if (solution != null) {
					process.database.clearRecent()
					process.nextStep = AlignUpperBodyTrackers(process, camera, cameraDelay, forwardPose, forwardPoseTrackers, solution.trackerRotations)
				}
			}
		}

		data class AlignUpperBodyTrackers(
			val process: VideoCalibrationProcess,
			val camera: Camera,
			val cameraDelay: Duration,
			val forwardPose: QuaternionD,
			val forwardPoseTrackers: List<TrackersSnapshot>,
			val leaningForwardPoseTrackers: List<TrackersSnapshot>,
		) : Step {
			override suspend fun enter() {
				process.observer.onAligningUpperBodyTrackers()
				process.database.clearRecent()
			}

			override suspend fun run() {
				process.observer.onAligningUpperBodyTrackers()

				val trackerAlignments = mutableMapOf<TrackerPosition, TrackerResetOverride>()
				for (tracker in process.trackers.upperBodyTrackers) {
					val solution =
						UpperBodyTrackerAligner()
							.solve(
								tracker.trackerPosition!!,
								forwardPose,
								forwardPoseTrackers,
								leaningForwardPoseTrackers,
							)

					if (solution == null) {
						error("Failed to align upper body tracker ${tracker.trackerPosition}")
					}

					// Apply reset to tracker
					tracker.resetsHandler.trackerResetOverride = solution
					trackerAlignments[tracker.trackerPosition!!] = solution

					process.observer.onTrackersAligned(process.trackers, trackerAlignments.keys)
				}

				process.nextStep = AlignRemainingTrackers(process, camera, cameraDelay, forwardPose, forwardPoseTrackers, trackerAlignments)
			}
		}

		data class AlignRemainingTrackers(
			val process: VideoCalibrationProcess,
			val camera: Camera,
			val cameraDelay: Duration,
			val forwardPose: QuaternionD,
			val forwardPoseTrackers: List<TrackersSnapshot>,
			val trackerAlignments: MutableMap<TrackerPosition, TrackerResetOverride>,
		) : Step {
			override suspend fun enter() {
				process.observer.onAligningRemainingTrackers()
			}

			override suspend fun run() {
				coroutineScope {
					for (tracker in process.trackers.nonUpperBodyTrackers) {
						if (trackerAlignments.containsKey(tracker.trackerPosition!!)) {
							continue
						}

						launch {
							val solution =
								NonUpperBodyTrackerAligner()
									.solve(
										tracker.trackerPosition!!,
										camera,
										forwardPose,
										forwardPoseTrackers,
										process.database,
										cameraDelay,
									)
							if (solution != null) {
								synchronized(trackerAlignments) {
									// Apply reset to tracker
									tracker.resetsHandler.trackerResetOverride =
										solution.trackerReset
									trackerAlignments[tracker.trackerPosition!!] =
										solution.trackerReset

									process.observer.onTrackersAligned(
										process.trackers,
										trackerAlignments.keys,
									)
								}
							}
						}
					}
				}

				if (process.trackers.nonUpperBodyTrackers.map { it.trackerPosition }.all { trackerAlignments.containsKey(it) }) {
					if (process.selectedProcess == Process.ALIGN_TRACKERS) {
						process.nextStep = Complete(process)
					} else {
						process.nextStep =
							OptimizeBodyProportions(
								process,
								camera,
								cameraDelay,
								trackerAlignments,
							)
					}
				}
			}
		}

		data class OptimizeBodyProportions(
			val process: VideoCalibrationProcess,
			val camera: Camera,
			val cameraDelay: Duration,
			val trackerAlignments: MutableMap<TrackerPosition, TrackerResetOverride>,
		) : Step {
			override suspend fun enter() {
				process.observer.onOptimizingBodyProportions()
			}

			override suspend fun run() {
				val solution =
					BodyProportionsOptimizer(process.debugOutput)
						.solve(
							process.database,
							camera,
							cameraDelay,
							process.initialHmdHeight,
							process.initialSkeletonOffsets,
							trackerAlignments,
						)
				if (solution != null) {
					process.server.addRunOnceOnTick {
						process.server.humanPoseManager.skeletonConfigManager.setOffsets(solution.skeletonOffsets)
					}

					process.step = Complete(process)
				}
			}
		}

		data class Complete(
			val process: VideoCalibrationProcess,
		) : Step {
			override suspend fun enter() {
				process.observer.onComplete()
			}
			override suspend fun run() {
			}
		}
	}

	private val logger = Logger(this::class.simpleName)

	private var step: Step? = null
	private var nextStep: Step? = Step.SolveCameraExtrinsic(this)

	suspend fun run() {
		logger.info("Video calibration process started...")

		while (true) {
			val nextStep = nextStep
			if (nextStep != null) {
				logger.info("Entering step ${nextStep::class.simpleName}...")
				step = nextStep
				try {
					nextStep.enter()
					this.nextStep = null
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					logger.info(
						"Entering step ${nextStep::class.simpleName} encountered error",
						e,
					)
				}
			} else {
				delay(DELAY_BETWEEN_RETRY)
			}

			val step = step
			if (step != null) {
				if (step is Step.Complete) {
					break
				}

				logger.info("Running step ${step::class.simpleName}...")
				try {
					step.run()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					logger.info(
						"Step ${step::class.simpleName} encountered error",
						e,
					)
				}
			}
		}
	}

	companion object {
		private val DELAY_BETWEEN_RETRY = 1.seconds
	}
}
