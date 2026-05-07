package dev.slimevr.tracking.videocalibration.mdns

import io.eiren.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.streams.asSequence

class MDNSBrowser(
	val serviceTypes: List<String>,
	val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) : AutoCloseable {

	private val logger = Logger(this::class.simpleName)

	// Must be accessed under synchronized(lock)
	private val lock = Any()
	private val endpoints = mutableSetOf<MDNSEndpoint>()

	/**
	 * Starts the mDNS browser.
	 */
	fun start() {
		scope.launch {
			logger.info("Starting mDNS browser...")

			val jmDNSs = trySetupListeners()

			try {
				awaitCancellation()
			} catch (e: CancellationException) {
				logger.info("mDNS browser cancelled")
				throw e
			} catch (e: Exception) {
				logger.warning("mDNS browser encountered exception", e)
				throw e
			} finally {
				logger.info("Stopping mDNS browser...")
				jmDNSs.forEach { it.close() }
				synchronized(lock) {
					endpoints.clear()
				}
			}
		}
	}

	/**
	 * Stops the mDNS browser.
	 */
	override fun close() {
		scope.cancel()
	}

	/**
	 * Starts JmDNS instances on all network interfaces. Best effort.
	 */
	private fun trySetupListeners(): List<JmDNS> {
		val networks =
			NetworkInterface.getNetworkInterfaces().asSequence()
				.filter { it.isUp }
				.flatMap { it.inetAddresses().asSequence() }
				.filter { it.isSiteLocalAddress }

		val jmDNSs = mutableListOf<JmDNS>()
		for (inetAddress in networks) {
			try {
				val jmDNS = JmDNS.create(inetAddress)
				for (serviceType in serviceTypes) {
					jmDNS.addServiceListener(serviceType, MyServiceListener(jmDNS, this))
				}
				jmDNSs.add(jmDNS)
				logger.info("Added mDNS listener to ${inetAddress.hostAddress}")
			} catch (e: Exception) {
				logger.warning("Failed to create mDNS listener on ${inetAddress.hostAddress}", e)
				// Ignore, don't rethrow
			}
		}

		return jmDNSs
	}

	/**
	 * Gets the endpoints that advertise a specified service type.
	 */
	fun getEndpoints(serviceType: String): List<MDNSEndpoint> {
		synchronized(lock) {
			return endpoints.filter { it.serviceType == serviceType }
		}
	}

	private class MyServiceListener(
		private val jmDNS: JmDNS,
		private val browser: MDNSBrowser,
	) : ServiceListener {

		private val logger = Logger(this::class.simpleName)

		override fun serviceAdded(event: ServiceEvent) {
			jmDNS.requestServiceInfo(event.type, event.name)
		}

		override fun serviceResolved(event: ServiceEvent) {
			synchronized(browser.lock) {
				for (inetAddress in event.info.inetAddresses) {
					if (inetAddress is Inet4Address) {
						logger.info("Found ${event.info.type} at ${inetAddress.hostAddress} port ${event.info.port}")
						val endpoint =
							MDNSEndpoint(event.info.type, inetAddress, event.info.port)
						browser.endpoints.add(endpoint)
					}
				}
			}
		}

		// TODO: This doesn't do what I thought it does. event.info.port and event.info.inetAddresses are empty
		override fun serviceRemoved(event: ServiceEvent) {
			logger.info("Removing ${event.info.type} port ${event.info.port}")
			synchronized(browser.lock) {
				logger.info("Before: ${browser.endpoints.size}")
				browser.endpoints.removeIf { endpoint ->
					endpoint.serviceType == event.info.type
				}
				logger.info("After: ${browser.endpoints.size}")
			}
		}
	}

	abstract class ServiceTypes {
		companion object {
			const val SIMPLE_WEBCAM = "_simple-webcam._tcp.local."
		}
	}
}
