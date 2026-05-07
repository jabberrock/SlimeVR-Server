package dev.slimevr.tracking.videocalibration.trackers

import dev.slimevr.VRServer
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.videocalibration.Database
import dev.slimevr.tracking.videocalibration.util.ScheduledInterval
import io.eiren.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Runnable
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Periodically captures a snapshot of the trackers.
 */
class TrackersSnapshotter(
	private val server: VRServer,
	private val interval: Duration,
	private val trackers: AssignedTrackers,
	private val database: Database,
) {
	private val _completed = CompletableDeferred<Unit>()
	val completed: Deferred<Unit> = _completed

	suspend fun run() {
		val listener =
			CaptureTrackersSnapshotOnServerTick(
				interval,
				trackers,
				database,
				onFailure = {
					_completed.completeExceptionally(it)
				},
			)
		server.addOnTick(listener)

		try {
			_completed.await()
		} catch (e: CancellationException) {
			_completed.cancel(e)
			throw e
		} finally {
			server.removeOnTick(listener)
		}
	}

	private class CaptureTrackersSnapshotOnServerTick(
		interval: Duration,
		private val trackers: AssignedTrackers,
		private val database: Database,
		private val onFailure: (Throwable) -> Unit,
	) : Runnable {

		private val logger = Logger(this::class.simpleName)

		private val scheduler = ScheduledInterval(interval)

		override fun run() {
			if (!scheduler.shouldInvoke()) {
				return
			}

			try {
				trackers.ensureTrackersAreGood()

				val snapshot =
					TrackersSnapshot(
						TimeSource.Monotonic.markNow(),
						trackers.controllers.associate { it.trackerPosition!! to makePositionalTracker(it) },
						trackers.trackers.associate { it.trackerPosition!! to makeIMUTracker(it) },
					)
				database.addTrackersSnapshot(snapshot)
			} catch (e: Exception) {
				logger.warning("Failed to create trackers snapshot", e)
				onFailure(e)
				return
			}
		}

		private fun makePositionalTracker(tracker: Tracker): PositionalTracker {
			if (!tracker.hasRotation) {
				error("Tracker at ${tracker.trackerPosition} has no rotation")
			}

			if (!tracker.hasPosition) {
				error("Tracker at ${tracker.trackerPosition} has no position")
			}

			val snapshot =
				PositionalTracker(
					tracker.getRawRotation().toDouble(),
					tracker.position.toDouble(),
				)

			return snapshot
		}

		private fun makeIMUTracker(tracker: Tracker): IMUTracker {
			if (!tracker.hasRotation) {
				error("Tracker at ${tracker.trackerPosition} has no rotation")
			}

			val snapshot = IMUTracker(tracker.getRawRotation().toDouble())

			return snapshot
		}
	}
}
