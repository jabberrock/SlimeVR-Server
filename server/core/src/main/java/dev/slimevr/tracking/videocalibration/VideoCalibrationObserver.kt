package dev.slimevr.tracking.videocalibration

import dev.slimevr.tracking.processor.config.SkeletonConfigOffsets
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.videocalibration.trackers.AssignedTrackers
import dev.slimevr.tracking.videocalibration.vision.Camera

interface VideoCalibrationObserver {
	fun onConnectingToServer()
	fun onConnectingToWebcam()
	fun onWaitingForUserStart()
	fun onSolvingCameraExtrinsic()
	fun onCapturingForwardPose()
	fun onCapturingLeaningForwardPose()
	fun onAligningUpperBodyTrackers()
	fun onAligningRemainingTrackers()
	fun onOptimizingBodyProportions()
	fun onComplete()

	fun onCameraExtrinsic(camera: Camera)
	fun onTrackersAligned(trackers: AssignedTrackers, aligned: Set<TrackerPosition>)
	fun onBodyProportions(bodyProportions: Map<SkeletonConfigOffsets, Pair<Float, Float>>)

	fun onMissingPositionalTrackersError(trackers: List<TrackerPosition>)
	fun onMissingRequiredIMUTrackersError(trackers: List<TrackerPosition>)
	fun onForwardAndLeaningForwardNotAligned(yawDifference: Double)
}
