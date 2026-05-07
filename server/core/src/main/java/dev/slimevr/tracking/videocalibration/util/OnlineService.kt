package dev.slimevr.tracking.videocalibration.util

import kotlinx.coroutines.Deferred

/**
 * Represents a service that connects and disconnects.
 *
 * This pattern allows us to suspend until the service enters a certain state, or
 * directly check for the state of the service.
 */
interface OnlineService {

	val connected: Deferred<Unit>
	val completed: Deferred<Unit>

	suspend fun run()
}
