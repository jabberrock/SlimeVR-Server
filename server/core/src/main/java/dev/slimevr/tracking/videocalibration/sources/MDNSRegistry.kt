package dev.slimevr.tracking.videocalibration.sources

import io.eiren.util.logging.LogManager
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener
import kotlin.collections.set

/**
 * Registry that listens for mDNS services.
 */
class MDNSRegistry(val serviceTypes: List<ServiceType>) {

	enum class ServiceType(val mDNSServiceType: String) {
		WEBCAM("_slimevr-camera._tcp.local."),
		;

		companion object {
			fun byMDNS(mDNSServiceType: String) = ServiceType.entries.firstOrNull { it.mDNSServiceType == mDNSServiceType }
		}
	}

	data class Service(
		val type: ServiceType,
		val host: Inet4Address,
		val port: Int,
	)

	private val lock = Any()
	private var running = false
	private val jmDNSs = mutableListOf<JmDNS>()
	private val services = mutableMapOf<ServiceType, Service>()

	/**
	 * Starts listening for mDNS services.
	 */
	fun start() {
		LogManager.info("Starting mDNS discovery...")

		synchronized(lock) {
			if (running) {
				error("mDNS registry is already running")
			}

			running = true

			jmDNSs.addAll(tryConnect())

			for (serviceType in serviceTypes) {
				LogManager.info("Listening for mDNS service $serviceType...")
				for (jmdns in jmDNSs) {
					jmdns.addServiceListener(serviceType.mDNSServiceType, MDNSServiceListener(jmdns, this))
				}
			}
		}

		LogManager.info("mDNS discovery started")
	}

	/**
	 * Stops listening for mDNS services.
	 */
	fun stop() {
		LogManager.info("Stopping mDNS discovery...")

		synchronized(lock) {
			for (jmdns in jmDNSs) {
				try {
					jmdns.close()
				} catch (e: Exception) {
					LogManager.warning("Failed to close mDNS at ${jmdns.inetAddress}", e)
				}
			}

			jmDNSs.clear()
			services.clear()
		}

		LogManager.info("mDNS discovery stopped")
	}

	/**
	 * Tries to create an mDNS listener on every network interface.
	 */
	private fun tryConnect(): List<JmDNS> {
		val jmDNSs = mutableListOf<JmDNS>()
		try {
			for (network in NetworkInterface.getNetworkInterfaces()) {
				if (network.isUp && !network.isLoopback) {
					for (address in network.inetAddresses) {
						if (address is Inet4Address) {
							LogManager.info("Listening for mDNS on network $address...")
							try {
								val jmdns = JmDNS.create(address)
								jmDNSs.add(jmdns)
							} catch (e: Exception) {
								LogManager.warning(
									"Failed to create mDNS instance for $address",
									e,
								)
							}
						}
					}
				}
			}
		} catch (e: Exception) {
			LogManager.warning("Failed to enumerate network instances", e)
		}

		return jmDNSs
	}

	/**
	 * Adds a newly discovered service.
	 */
	private fun addService(service: Service) {
		synchronized(lock) {
			if (running) {
				LogManager.debug("Added mDNS service $service")
				services[service.type] = service
			}
		}
	}

	/**
	 * Removes a service.
	 */
	private fun removeService(serviceType: ServiceType) {
		synchronized(lock) {
			LogManager.debug("Removed mDNS service $serviceType")
			services.remove(serviceType)
		}
	}

	/**
	 * Gets a discovered service.
	 */
	fun findService(serviceType: ServiceType): Service? {
		synchronized(lock) {
			return if (running) services[serviceType] else null
		}
	}

	private class MDNSServiceListener(val jmDNS: JmDNS, val registry: MDNSRegistry) : ServiceListener {

		override fun serviceAdded(event: ServiceEvent) {
			val serviceType = ServiceType.byMDNS(event.type) ?: return

			LogManager.debug("Resolving mDNS service $serviceType on ${jmDNS.inetAddress}...")
			jmDNS.requestServiceInfo(event.type, event.name)
		}

		override fun serviceRemoved(event: ServiceEvent) {
			val serviceType = ServiceType.byMDNS(event.type) ?: return
			registry.removeService(serviceType)
		}

		override fun serviceResolved(event: ServiceEvent) {
			val serviceType = ServiceType.byMDNS(event.type) ?: return

			val info = event.info
			val host = info.inetAddresses.filterIsInstance<Inet4Address>().firstOrNull() ?: return
			val port = info.port
			val service = Service(serviceType, host, port)
			registry.addService(service)
		}
	}
}
