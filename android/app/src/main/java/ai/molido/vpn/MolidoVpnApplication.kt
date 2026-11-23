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
import ai.molido.vpn.core.GitHubUpdateChecker
import ai.molido.vpn.core.DiagnosticsObservabilityCore
import ai.molido.vpn.core.NetworkAssessment
import ai.molido.vpn.core.NetworkIntelligenceCore
import ai.molido.vpn.core.NetworkObservation
import ai.molido.vpn.core.NetworkPolicy
import ai.molido.vpn.core.NetworkPolicySettingsStore
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
    @Volatile private var updateKeyPinned = false
    val updateChecker by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GitHubUpdateChecker(updateTrust, updateInstaller, trustKeyProvisioned = { updateKeyPinned })
    }

    /** Pins the build-embedded update key once; a different key later is refused by the trust store. */
    private fun pinUpdateKeyFromBuild() {
        val encoded = BuildConfig.UPDATE_PUBLIC_KEY_B64
        if (encoded.isBlank()) return
        updateKeyPinned = try {
            trustStore.pinUpdatePublicKey(java.util.Base64.getDecoder().decode(encoded))
            trustStore.loadUpdatePublicKey()
            true
        } catch (_: Exception) {
            diagnostics.record("UPDATE_KEY_PIN_FAILED", "Update trust key could not be pinned", System.currentTimeMillis())
            false
        }
    }

    /** Caller supplies operator-provisioned HTTPS endpoint and ephemeral device token. */
    fun fetchRemoteConfiguration(baseUrl: String, deviceToken: String): ControlPlaneFetchResult =
        controlPlaneClient.fetchAndVerify(baseUrl, deviceToken, System.currentTimeMillis() / 1000L)

    @Volatile var onNetworkAssessmentChanged: (() -> Unit)? = null
    /** Survives activity recreation (rotation, reopening) for as long as the tunnel process lives. */
    @Volatile var handshakeConfirmed = false

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
        vpnExecutor.execute { pinUpdateKeyFromBuild() }
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
