package ai.molido.vpn

import android.app.Application
import android.net.ConnectivityManager
import ai.molido.vpn.core.AndroidNetworkObservationMonitor
import ai.molido.vpn.core.AdaptivePathPolicyCore
import ai.molido.vpn.core.PathDecision
import ai.molido.vpn.core.AndroidSecureProfileStore
import ai.molido.vpn.core.AndroidSecureTrustStore
import ai.molido.vpn.core.AndroidUpdateTrust
import ai.molido.vpn.core.AndroidUpdateInstaller
import ai.molido.vpn.core.ControlPlaneClient
import ai.molido.vpn.core.ControlPlaneFetchResult
import ai.molido.vpn.core.DiagnosticsObservabilityCore
import ai.molido.vpn.core.EngineCapability
import ai.molido.vpn.core.EngineCapabilityCatalog
import ai.molido.vpn.core.EngineSlot
import ai.molido.vpn.core.NetworkAssessment
import ai.molido.vpn.core.NetworkIntelligenceCore
import ai.molido.vpn.core.NetworkObservation
import ai.molido.vpn.core.NetworkPolicy
import ai.molido.vpn.core.NetworkPolicySettingsStore
import ai.molido.vpn.core.RecoveryAttemptGate
import ai.molido.vpn.core.SessionSupervisor
import ai.molido.vpn.core.SessionSnapshot
import ai.molido.vpn.core.SessionState
import ai.molido.vpn.core.RecoveryAction
import ai.molido.vpn.core.RecoveryAttemptToken
import ai.molido.vpn.core.ResilienceRecoveryCore
import ai.molido.vpn.core.TunOwnershipCoordinator
import ai.molido.vpn.core.VpnSessionController
import ai.molido.vpn.engine.wireguard.WireGuardEngineAdapter
import ai.molido.vpn.core.AndroidRecoveryAttemptStateStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

class MolidoVpnApplication : Application() {
    lateinit var vpnSessions: VpnSessionController
        private set
    lateinit var vpnExecutor: ExecutorService
        private set
    private lateinit var healthMonitorExecutor: ScheduledExecutorService
    private lateinit var recoveryExecutor: ScheduledExecutorService
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
    private val recoveryLock = Any()
    @Volatile private var recoveryConfiguration: CharArray? = null
    @Volatile private var pendingRecoveryToken: RecoveryAttemptToken? = null
    private var recoveryScheduled = false
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
        resumePendingRecoveryWhenNetworkValidated()
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
        recoveryExecutor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "molido-vpn-recovery").apply { isDaemon = true }
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
            diagnostics = diagnostics,
            recoveryAttemptGate = RecoveryAttemptGate(store = AndroidRecoveryAttemptStateStore(
                getSharedPreferences("molido_recovery_state", MODE_PRIVATE)
            ))
        )
        vpnSessions.addStateObserver(::handleRecoverySessionState)
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
                // UI observers are outside the recovery trust boundary. Their failure
                // must never suppress recovery after a validated network transition.
                runCatching { onNetworkAssessmentChanged?.invoke() }
                resumePendingRecoveryWhenNetworkValidated()
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


    /** Keep a short-lived in-memory profile copy for recovery in this process only. */
    fun setRecoveryConfiguration(configuration: String) {
        require(configuration.isNotBlank())
        synchronized(recoveryLock) {
            recoveryConfiguration?.fill(0.toChar())
            recoveryConfiguration = configuration.toCharArray()
        }
    }

    /** A manual disconnect is authoritative and cancels pending automatic recovery. */
    fun clearRecoveryConfiguration() {
        val token: RecoveryAttemptToken?
        synchronized(recoveryLock) {
            recoveryConfiguration?.fill(0.toChar())
            recoveryConfiguration = null
            token = pendingRecoveryToken
            pendingRecoveryToken = null
            recoveryScheduled = false
        }
        if (token != null) vpnSessions.completeRecoveryAttempt(token, connected = false)
    }

    private fun handleRecoverySessionState(snapshot: SessionSnapshot) {
        when (snapshot.state) {
            SessionState.FAILED -> scheduleAutomaticRecovery()
            SessionState.IDLE -> {
                val hasPending = synchronized(recoveryLock) { pendingRecoveryToken != null }
                if (!hasPending) clearRecoveryConfiguration()
            }
            else -> Unit
        }
    }

    private fun scheduleAutomaticRecovery() {
        synchronized(recoveryLock) {
            if (recoveryConfiguration == null || pendingRecoveryToken != null || recoveryScheduled) return
            recoveryScheduled = true
        }
        val token = vpnSessions.beginRecoveryAttempt()
        if (token == null) {
            synchronized(recoveryLock) { recoveryScheduled = false }
            diagnostics.record("RECOVERY_LIMIT_REACHED", "No bounded recovery attempt is available", System.currentTimeMillis())
            return
        }
        synchronized(recoveryLock) { pendingRecoveryToken = token }
        try {
            vpnExecutor.execute {
                val plan = runCatching { vpnSessions.prepareRecovery(attempt = token.attempt - 1) }.getOrNull()
                synchronized(recoveryLock) { recoveryScheduled = false }
                if (plan == null || plan.action == RecoveryAction.NONE || plan.action == RecoveryAction.STOP_RETRIES) {
                    finishRecoveryAttempt(token, connected = false)
                } else if (plan.action == RecoveryAction.WAIT_FOR_NETWORK) {
                    diagnostics.record("RECOVERY_WAITING_FOR_NETWORK", "Previous session cleaned; waiting for fresh validated connectivity", System.currentTimeMillis())
                    resumePendingRecoveryWhenNetworkValidated()
                } else {
                    scheduleRecoveryConnect(token, plan.delayMillis)
                }
            }
        } catch (_: RejectedExecutionException) {
            diagnostics.record("RECOVERY_EXECUTOR_REJECTED", "VPN control executor rejected recovery preparation", System.currentTimeMillis())
            finishRecoveryAttempt(token, connected = false)
        }
    }

    private fun resumePendingRecoveryWhenNetworkValidated() {
        val token = pendingRecoveryToken ?: return
        val assessment = latestNetworkAssessment()
        if (assessment.status != ai.molido.vpn.core.NetworkAssessmentStatus.VALIDATED) return
        if (AdaptivePathPolicyCore.evaluate(assessment, selectedNetworkPolicy.get()).decision != PathDecision.ALLOW) return
        val plan = ResilienceRecoveryCore.plan(
            SessionState.FAILED, assessment, attempt = token.attempt - 1
        )
        if (plan.action == RecoveryAction.SCHEDULE_RETRY) scheduleRecoveryConnect(token, plan.delayMillis)
    }

    private fun scheduleRecoveryConnect(token: RecoveryAttemptToken, baseDelayMillis: Long) {
        synchronized(recoveryLock) {
            if (pendingRecoveryToken != token || recoveryScheduled) return
            recoveryScheduled = true
        }
        val jitteredDelay = (baseDelayMillis * ThreadLocalRandom.current().nextDouble(0.8, 1.2))
            .toLong().coerceAtMost(ResilienceRecoveryCore.MAX_DELAY_MILLIS)
        try {
            recoveryExecutor.schedule({
                try {
                    vpnExecutor.execute retryTask@{
                        val stillPending = synchronized(recoveryLock) {
                            pendingRecoveryToken == token && recoveryConfiguration != null
                        }
                        if (stillPending) {
                            // Use one immutable assessment for both validation and policy
                            // evaluation; a network transition cannot split the two checks.
                            val assessment = latestNetworkAssessment()
                            if (assessment.status != ai.molido.vpn.core.NetworkAssessmentStatus.VALIDATED ||
                                AdaptivePathPolicyCore.evaluate(assessment, selectedNetworkPolicy.get()).decision != PathDecision.ALLOW
                            ) {
                                synchronized(recoveryLock) {
                                    if (pendingRecoveryToken == token) recoveryScheduled = false
                                }
                                diagnostics.record("RECOVERY_RECHECK_NETWORK", "Network changed before retry; waiting for validation", System.currentTimeMillis())
                            } else {
                                val configuration = synchronized(recoveryLock) {
                                    recoveryConfiguration?.concatToString()
                                }
                                val connected = try {
                                    configuration != null && vpnSessions.connect(EngineSlot.WIREGUARD, configuration)
                                } catch (_: Throwable) {
                                    false
                                }
                                finishRecoveryAttempt(token, connected)
                                if (!connected && vpnSessions.snapshot().state == SessionState.FAILED) {
                                    scheduleAutomaticRecovery()
                                }
                            }
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    diagnostics.record("RECOVERY_EXECUTOR_REJECTED", "VPN control executor rejected a scheduled retry", System.currentTimeMillis())
                    finishRecoveryAttempt(token, connected = false)
                }
            }, jitteredDelay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            diagnostics.record("RECOVERY_EXECUTOR_REJECTED", "Recovery scheduler rejected a retry", System.currentTimeMillis())
            finishRecoveryAttempt(token, connected = false)
        }
    }

    private fun finishRecoveryAttempt(token: RecoveryAttemptToken, connected: Boolean) {
        val persisted = vpnSessions.completeRecoveryAttempt(token, connected)
        if (!persisted) diagnostics.record("RECOVERY_STATE_PERSIST_FAILED", "Retry state could not be committed; automatic retries remain fail-closed", System.currentTimeMillis())
        synchronized(recoveryLock) {
            if (pendingRecoveryToken == token) pendingRecoveryToken = null
            recoveryScheduled = false
        }
        diagnostics.record(
            if (connected) "RECOVERY_RECONNECTED" else "RECOVERY_ATTEMPT_FINISHED",
            if (connected) "WireGuard session re-entered RUNNING after validated network and bounded retry"
            else "Recovery attempt ended without a confirmed RUNNING session",
            System.currentTimeMillis()
        )
    }

    override fun onTerminate() {
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        clearRecoveryConfiguration()
        if (::recoveryExecutor.isInitialized) recoveryExecutor.shutdownNow()
        if (::healthMonitorExecutor.isInitialized) healthMonitorExecutor.shutdownNow()
        if (::vpnExecutor.isInitialized) vpnExecutor.shutdown()
        super.onTerminate()
    }
}
