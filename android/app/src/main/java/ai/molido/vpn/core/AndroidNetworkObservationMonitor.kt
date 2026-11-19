package ai.molido.vpn.core

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.Executor

/**
 * Lifecycle-bound passive network monitor. Register while the app is active and
 * unregister on stop; it never performs connectivity probes or sends telemetry.
 */
class AndroidNetworkObservationMonitor(
    private val connectivityManager: ConnectivityManager,
    private val executor: Executor,
    private val onObservation: (NetworkObservation?) -> Unit,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            publish(network, capabilities)
        }
        override fun onLost(network: Network) {
            publish(connectivityManager.activeNetwork)
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder().clearCapabilities().build()
        connectivityManager.registerNetworkCallback(request, callback, executor)
        registered = true
        publish(connectivityManager.activeNetwork)
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        connectivityManager.unregisterNetworkCallback(callback)
        registered = false
    }

    private fun publish(network: Network?, supplied: NetworkCapabilities? = null) {
        if (network == null) {
            onObservation(null)
            return
        }
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
        onObservation(
            NetworkObservation(
                transport = transport,
                validation = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    NetworkValidation.VALIDATED else NetworkValidation.NOT_VALIDATED,
                observedAtEpochMillis = nowEpochMillis(),
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                downstreamKbps = caps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
                upstreamKbps = caps.linkUpstreamBandwidthKbps.takeIf { it > 0 }
            )
        )
    }
}
