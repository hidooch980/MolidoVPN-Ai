package ai.molido.vpn

import android.app.Application
import android.net.ConnectivityManager
import ai.molido.vpn.core.AndroidNetworkObservationMonitor
import ai.molido.vpn.core.AndroidSecureProfileStore
import ai.molido.vpn.core.AndroidSecureTrustStore
import ai.molido.vpn.core.AndroidUpdateTrust
import ai.molido.vpn.core.AndroidUpdateInstaller
import ai.molido.vpn.core.ControlPlaneClient
import ai.molido.vpn.core.ControlPlaneFetchResult
import ai.molido.vpn.core.DiagnosticsObservabilityCore
import ai.molido.vpn.core.EngineCapability
import ai.molido.vpn.core.EngineCapabilityCatalog
import ai.molido.vpn.core.NetworkAssessment
import ai.molido.vpn.core.NetworkIntelligenceCore
import ai.molido.vpn.core.NetworkObservation
import ai.molido.vpn.core.NetworkPolicy
import ai.molido.vpn.core.NetworkPolicySettingsStore
import ai.molido.vpn.core.SessionSupervisor
import ai.molido.vpn.core.TunOwnershipCoordinator
import ai.molido.vpn.core.VpnSessionController
import ai.molido.vpn.engine.wireguard.WireGuardEngineAdapter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MolidoVpnApplication : Application() {
    lateinit var vpnSessions: VpnSessionController
        private set
    lateinit var vpnExecutor: ExecutorService
        private set
    private lateinit var healthMonitorExecutor: ScheduledExecutorService
    lateinit var networkMonitor: AndroidNetworkObservationMonitor
        private set
    /** Live capability list; entries are derived from adapters actually wired at runtime. */
    lateinit var engineCapabilities: List<EngineCapability>
        private set
    lateinit var wireGuardAdapter: WireGuardEngineAdapter
        private set
    /** Shared single-TUN lease coordinator; all future engine adapters must receive this instance. */
    lateinit var tunOwnershipCoordinator: TunOwnershipCoordinator
        private set

    private val observation = AtomicReference<NetworkObservation?>(null)
    private val selectedNetworkPolicy = AtomicReference(NetworkPolicy.ANY_VALIDATED)
    private val networkPolicySettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val preferences = getSharedPreferences(NetworkPolicySettingsStore.PREFERENCES_NAME, MODE_PRIVATE)
        NetworkPolicySettingsStore(
            readString = { key -> preferences.getString(key, null) },
            writeString = { key, value -> preferences.edit().putString(key, value).commit() }
        )
    }
    val diagnostics = DiagnosticsObservabilityCore(enabled = false)
    val secureProfileStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AndroidSecureProfileStore(this) }
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

    fun setNetworkPolicy(policy: NetworkPolicy): Boolean {
        if (!networkPolicySettings.save(policy)) return false
        selectedNetworkPolicy.set(policy)
        return true
    }

    /** Returns the currently active policy so recreated Activities render truthful settings. */
    fun currentNetworkPolicy(): NetworkPolicy = selectedNetworkPolicy.get()

    override fun onCreate() {
        super.onCreate()
        selectedNetworkPolicy.set(networkPolicySettings.load())
        vpnExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "molido-vpn-control").apply { isDaemon = true }
        }
        healthMonitorExecutor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "molido-vpn-health-monitor").apply { isDaemon = true }
        }
        tunOwnershipCoordinator = TunOwnershipCoordinator()
        wireGuardAdapter = WireGuardEngineAdapter(this, tunOwnershipCoordinator)
        val registeredAdapters = listOf(wireGuardAdapter)
        engineCapabilities = EngineCapabilityCatalog.build(registeredAdapters)
        vpnSessions = VpnSessionController(
            supervisor = SessionSupervisor(tunOwnershipCoordinator),
            adapters = registeredAdapters,
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
                vpnSessions.observeNetworkAssessment(assessment)
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
        // Single daemon worker and fixed-delay cadence prevent overlapping health samples.
        healthMonitorExecutor.scheduleWithFixedDelay({
            runCatching { vpnSessions.observeActiveTunnelHealth() }
        }, 10L, 15L, TimeUnit.SECONDS)
    }

    override fun onTerminate() {
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        if (::healthMonitorExecutor.isInitialized) healthMonitorExecutor.shutdownNow()
        if (::vpnExecutor.isInitialized) vpnExecutor.shutdown()
        super.onTerminate()
    }
}
