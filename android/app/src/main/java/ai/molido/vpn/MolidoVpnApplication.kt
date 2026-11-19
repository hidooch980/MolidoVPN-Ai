package ai.molido.vpn

import android.app.Application
import android.net.ConnectivityManager
import ai.molido.vpn.core.AndroidNetworkObservationMonitor
import ai.molido.vpn.core.AndroidSecureTrustStore
import ai.molido.vpn.core.AndroidUpdateTrust
import ai.molido.vpn.core.AndroidUpdateInstaller
import ai.molido.vpn.core.ControlPlaneClient
import ai.molido.vpn.core.ControlPlaneFetchResult
import ai.molido.vpn.core.DiagnosticsObservabilityCore
import ai.molido.vpn.core.NetworkAssessment
import ai.molido.vpn.core.NetworkIntelligenceCore
import ai.molido.vpn.core.NetworkObservation
import ai.molido.vpn.core.NetworkPolicy
import ai.molido.vpn.core.SessionSupervisor
import ai.molido.vpn.core.VpnSessionController
import ai.molido.vpn.engine.wireguard.WireGuardEngineAdapter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MolidoVpnApplication : Application() {
    lateinit var vpnSessions: VpnSessionController
        private set
    lateinit var vpnExecutor: ExecutorService
        private set
    lateinit var networkMonitor: AndroidNetworkObservationMonitor
        private set

    private val observation = AtomicReference<NetworkObservation?>(null)
    private val selectedNetworkPolicy = AtomicReference(NetworkPolicy.ANY_VALIDATED)
    val diagnostics = DiagnosticsObservabilityCore(enabled = false)
    val trustStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AndroidSecureTrustStore(this) }
    val controlPlaneClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ControlPlaneClient(trustStore) }
    val updateTrust by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AndroidUpdateTrust(this, trustStore) }
    val updateInstaller by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AndroidUpdateInstaller(this, updateTrust) }

    /** Caller supplies operator-provisioned HTTPS endpoint and ephemeral device token. */
    fun fetchRemoteConfiguration(baseUrl: String, deviceToken: String): ControlPlaneFetchResult =
        controlPlaneClient.fetchAndVerify(baseUrl, deviceToken, System.currentTimeMillis() / 1000L)

    @Volatile var onNetworkAssessmentChanged: (() -> Unit)? = null

    fun latestNetworkAssessment(): NetworkAssessment =
        NetworkIntelligenceCore.assess(observation.get(), System.currentTimeMillis())

    fun setNetworkPolicy(policy: NetworkPolicy) {
        selectedNetworkPolicy.set(policy)
    }

    override fun onCreate() {
        super.onCreate()
        vpnExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "molido-vpn-control").apply { isDaemon = true }
        }
        vpnSessions = VpnSessionController(
            supervisor = SessionSupervisor(),
            adapters = listOf(WireGuardEngineAdapter(this)),
            networkAssessmentProvider = { latestNetworkAssessment() },
            networkPolicyProvider = { selectedNetworkPolicy.get() },
            diagnostics = diagnostics
        )
        val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkMonitor = AndroidNetworkObservationMonitor(
            connectivityManager = connectivity,
            onObservation = { current ->
                observation.set(current)
                val assessment = latestNetworkAssessment()
                diagnostics.record(
                    "NETWORK_OBSERVED",
                    "status=${assessment.status};transport=${assessment.transport ?: "unknown"}",
                    System.currentTimeMillis()
                )
                onNetworkAssessmentChanged?.invoke()
            }
        )
        // Keep observing the default network while the VPN session is active,
        // even when the Activity is backgrounded.
        networkMonitor.start()
    }

    override fun onTerminate() {
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        if (::vpnExecutor.isInitialized) vpnExecutor.shutdown()
        super.onTerminate()
    }
}
