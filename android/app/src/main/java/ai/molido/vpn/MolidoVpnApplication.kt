package ai.molido.vpn

import android.app.Application
import android.net.ConnectivityManager
import ai.molido.vpn.core.AndroidNetworkObservationMonitor
import ai.molido.vpn.core.NetworkAssessment
import ai.molido.vpn.core.NetworkAssessmentStatus
import ai.molido.vpn.core.NetworkIntelligenceCore
import ai.molido.vpn.core.SessionSupervisor
import ai.molido.vpn.core.VpnSessionController
import ai.molido.vpn.engine.wireguard.WireGuardEngineAdapter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Owns process-wide VPN control and passive, lifecycle-bound network observation. */
class MolidoVpnApplication : Application() {
    lateinit var vpnSessions: VpnSessionController
        private set
    lateinit var vpnExecutor: ExecutorService
        private set
    lateinit var networkMonitor: AndroidNetworkObservationMonitor
        private set

    private val assessment = AtomicReference(NetworkAssessment(NetworkAssessmentStatus.UNKNOWN))

    fun latestNetworkAssessment(): NetworkAssessment = assessment.get()

    override fun onCreate() {
        super.onCreate()
        vpnExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "molido-vpn-control").apply { isDaemon = true }
        }
        vpnSessions = VpnSessionController(
            supervisor = SessionSupervisor(),
            adapters = listOf(WireGuardEngineAdapter(this))
        )
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkMonitor = AndroidNetworkObservationMonitor(
            connectivityManager = connectivity,
            executor = vpnExecutor,
            onObservation = { observation ->
                assessment.set(NetworkIntelligenceCore.assess(observation, System.currentTimeMillis()))
            }
        )
    }

    override fun onTerminate() {
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        vpnExecutor.shutdown()
        super.onTerminate()
    }
}
