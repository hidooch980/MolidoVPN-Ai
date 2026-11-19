package ai.molido.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Reads only Android's current active-network capabilities. It performs no active
 * probing and does not inspect DNS queries, destinations, or application traffic.
 */
class AndroidNetworkObservationCollector(
    context: Context,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun collect(): NetworkObservation? {
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.VPN
            else -> NetworkTransport.OTHER
        }
        val validation = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            NetworkValidation.VALIDATED
        } else {
            NetworkValidation.NOT_VALIDATED
        }

        return NetworkObservation(
            transport = transport,
            validation = validation,
            observedAtEpochMillis = nowEpochMillis(),
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            downstreamKbps = capabilities.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            upstreamKbps = capabilities.linkUpstreamBandwidthKbps.takeIf { it > 0 }
        )
    }
}
