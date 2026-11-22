package ai.molido.vpn.core

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

class AndroidNetworkObservationMonitor(
    private val connectivityManager: ConnectivityManager,
    private val onObservation: (NetworkObservation?) -> Unit,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    private var registered = false
    private var observedDefaultNetwork: Network? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(this@AndroidNetworkObservationMonitor) {
                if (registered) publishIfDefault(network)
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(this@AndroidNetworkObservationMonitor) {
                if (registered && network == connectivityManager.activeNetwork) {
                    publish(network, capabilities)
                }
            }
        }

        override fun onLost(network: Network) {
            synchronized(this@AndroidNetworkObservationMonitor) {
                if (!registered) return
                // activeNetwork may already be null or point at a replacement by the
                // time onLost is delivered; compare against the last observed default.
                if (network == observedDefaultNetwork) {
                    val current = connectivityManager.activeNetwork
                    observedDefaultNetwork = current
                    publish(current)
                }
            }
        }
    }

    @Synchronized fun start() {
        if (registered) return
        // This API observes only the system default network and is available at minSdk 26.
        registered = true
        try {
            connectivityManager.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
            publish(connectivityManager.activeNetwork)
        } catch (failure: Exception) {
            registered = false
            observedDefaultNetwork = null
            throw failure
        }
    }

    @Synchronized fun stop() {
        if (!registered) return
        // Gate callbacks before unregistering so a queued callback cannot publish stale data.
        registered = false
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } finally {
            observedDefaultNetwork = null
            deliverNetworkObservationSafely(onObservation, null)
        }
    }

    private fun publishIfDefault(network: Network) {
        if (network == connectivityManager.activeNetwork) publish(network)
    }

    private fun publish(network: Network?, supplied: NetworkCapabilities? = null) {
        if (network == null) {
            observedDefaultNetwork = null
            onObservation(null)
            return
        }
        if (network != connectivityManager.activeNetwork && supplied == null) return
        observedDefaultNetwork = network
        val caps = supplied ?: connectivityManager.getNetworkCapabilities(network)
        if (caps == null) {
            onObservation(null)
            return
        }
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            else -> NetworkTransport.OTHER
        }
        deliverNetworkObservationSafely(onObservation, NetworkObservation(
            transport = transport,
            validation = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                NetworkValidation.VALIDATED else NetworkValidation.NOT_VALIDATED,
            observedAtEpochMillis = nowEpochMillis(),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            downstreamKbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            upstreamKbps = caps.linkUpstreamBandwidthKbps.takeIf { it > 0 }
        ))
    }
}

/**
 * Isolates platform/network lifecycle callbacks from consumer code. A UI observer
 * exception must not leak a registered ConnectivityManager callback or crash its thread.
 */
internal fun deliverNetworkObservationSafely(
    observer: (NetworkObservation?) -> Unit,
    observation: NetworkObservation?
): Boolean = try {
    observer(observation)
    true
} catch (_: Exception) {
    false
}
