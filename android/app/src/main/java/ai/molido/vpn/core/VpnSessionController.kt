package ai.molido.vpn.core

class VpnSessionController(
    private val supervisor: SessionSupervisor,
    adapters: Collection<EngineAdapter>,
    private val networkAssessmentProvider: (() -> NetworkAssessment)? = null,
    private val networkPolicyProvider: () -> NetworkPolicy = { NetworkPolicy.ANY_VALIDATED },
    private val diagnostics: DiagnosticsObservabilityCore? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val recoveryAttemptGate: RecoveryAttemptGate = RecoveryAttemptGate()
) {
    private val adaptersBySlot: Map<EngineSlot, EngineAdapter> =
        adapters.associateBy { it.slot }.also { mapped ->
            require(mapped.size == adapters.size) { "Duplicate engine adapter slot" }
        }
    private var activeSession: EngineSession? = null
    private var activeOwner: EngineSlot? = null
    private var lastPathDecision: AdaptivePathDecision? = null
    private var lastNetworkAssessment: NetworkAssessment? = null
    private val pendingEngineFailures = mutableSetOf<EngineSlot>()

    /** UI observers receive lifecycle snapshots; callbacks must not block engine work. */
    @Volatile
    var onSessionStateChanged: ((SessionSnapshot) -> Unit)? = null
        set(value) {
            field = value
            notifyStateObserver(value, supervisor.snapshot())
        }

    private fun publishState() {
        notifyStateObserver(onSessionStateChanged, supervisor.snapshot())
    }

    /**
     * UI observers are outside the VPN lifecycle trust boundary. A broken observer must
     * never interrupt TUN startup, stop, or ownership release.
     */
    private fun notifyStateObserver(
        observer: ((SessionSnapshot) -> Unit)?,
        current: SessionSnapshot
    ) {
        try {
            observer?.invoke(current)
        } catch (_: Exception) {
            runCatching {
                record("SESSION_OBSERVER_FAILED", "A session-state observer threw an exception")
            }
        }
    }

    init {
        adaptersBySlot.values.filterIsInstance<EngineFailureAwareAdapter>().forEach { aware ->
            aware.setFailureListener {
                reportEngineFailure(aware)
            }
        }
    }

    @Synchronized
    private fun reportEngineFailure(adapter: EngineFailureAwareAdapter) {
        val slot = adaptersBySlot.entries.firstOrNull { it.value === adapter }?.key ?: return
        val snapshot = supervisor.snapshot()
        // A callback may arrive synchronously from start() before its session handle
        // is returned. Buffer it while this engine owns the RESERVED slot.
        if (snapshot.owner == slot && snapshot.state == SessionState.RESERVED && activeSession == null) {
            pendingEngineFailures.add(slot)
            return
        }
        val owner = activeOwner ?: return
        if (owner != slot || activeSession == null) return
        // Ignore intentional shutdown callbacks and duplicate/late DOWN notifications.
        if (snapshot.state != SessionState.RUNNING) return
        if (supervisor.markFailed(owner)) {
            record("ENGINE_SESSION_FAILED", "Engine reported unexpected shutdown; TUN ownership retained")
            publishState()
        }
    }

    @Synchronized
    fun connect(slot: EngineSlot, configuration: String): Boolean {
        if (activeSession != null) return activeOwner == slot && supervisor.snapshot().state == SessionState.RUNNING
        val adapter = adaptersBySlot[slot] ?: return false
        if (supervisor.requiresTunLease() && adapter !is TunLeaseAwareEngineAdapter) {
            record("ENGINE_TUN_LEASE_UNSUPPORTED", "Registered engine does not accept the shared TUN lease")
            return false
        }
        val assessment = networkAssessmentProvider?.invoke()
        if (assessment != null) {
            val decision = AdaptivePathPolicyCore.evaluate(assessment, networkPolicyProvider())
            lastPathDecision = decision
            if (decision.decision != PathDecision.ALLOW) {
                record("CONNECT_BLOCKED", decision.reason)
                return false
            }
        }
        if (!supervisor.claim(slot)) {
            record("SESSION_CLAIM_REJECTED", "Exclusive session ownership unavailable")
            return false
        }
        publishState()
        val session = try {
            if (supervisor.requiresTunLease()) {
                val lease = supervisor.tunLeaseFor(slot)
                    ?: error("Shared TUN lease reservation is missing")
                (adapter as TunLeaseAwareEngineAdapter).start(configuration, lease)
            } else {
                adapter.start(configuration)
            }
        } catch (failure: Throwable) {
            pendingEngineFailures.remove(slot)
            supervisor.markFailed(slot)
            val cleanupSession = (failure as? EngineStartException)?.cleanupSession
            if (cleanupSession != null) {
                activeSession = cleanupSession
                activeOwner = slot
            } else if (supervisor.requiresTunLease()) {
                val cleanupConfirmed = (failure as? EngineStartException)?.cleanupConfirmed == true
                if (cleanupConfirmed) {
                    check(supervisor.releaseUnstartedReservation(
                        slot,
                        backendCleanupConfirmed = true
                    )) {
                        "Adapter reported cleanup, but shared TUN reservation release was not confirmed"
                    }
                } else {
                    // No cleanup handle and no positive transport acknowledgement:
                    // retain exclusive ownership rather than assume the TUN is down.
                    activeOwner = slot
                    record("STARTUP_CLEANUP_UNCONFIRMED", "Startup failed without cleanup proof; shared TUN ownership retained")
                }
            } else {
                supervisor.beginStopping(slot)
                supervisor.release(slot)
            }
            record("ENGINE_START_FAILED", "Engine startup failed; sensitive details omitted")
            publishState()
            throw failure
        }
        if (!supervisor.markRunning(slot)) {
            pendingEngineFailures.remove(slot)
            supervisor.markFailed(slot)
            supervisor.beginStopping(slot)
            try {
                session.stop()
            } catch (failure: Throwable) {
                activeSession = session
                activeOwner = slot
                supervisor.markFailed(slot)
                record("START_ROLLBACK_PENDING", "Startup rollback requires cleanup retry")
                publishState()
                throw failure
            }
            if (!supervisor.release(slot)) {
                // Preserve the only idempotent cleanup handle until the ownership
                // authority confirms that the shared lease is actually released.
                activeSession = session
                activeOwner = slot
                supervisor.markFailed(slot)
                record("START_ROLLBACK_PENDING", "Ownership release was not confirmed; cleanup handle retained")
                publishState()
                return false
            }
            record("START_ROLLBACK_COMPLETE", "Session startup rolled back")
            publishState()
            return false
        }
        activeSession = session
        activeOwner = slot
        if (pendingEngineFailures.remove(slot)) {
            supervisor.markFailed(slot)
            record("ENGINE_SESSION_FAILED", "Engine failed during startup completion; TUN ownership retained")
            publishState()
            return false
        }
        record("SESSION_STARTED", "VPN engine session entered RUNNING state")
        publishState()
        return true
    }

    @Synchronized
    fun disconnect(): Boolean {
        val owner = activeOwner ?: return supervisor.snapshot().state == SessionState.IDLE
        if (!supervisor.beginStopping(owner)) return false
        try {
            activeSession?.stop()
        } catch (failure: Throwable) {
            supervisor.markFailed(owner)
            record("SESSION_STOP_FAILED", "Cleanup failed; ownership retained for retry")
            publishState()
            throw failure
        }
        if (!supervisor.release(owner)) {
            // Do not discard the only retry handle while the coordinator still holds
            // the lease. FAILED permits a subsequent disconnect() to retry idempotently.
            supervisor.markFailed(owner)
            record("SESSION_RELEASE_PENDING", "Transport stopped but shared ownership release was not confirmed")
            publishState()
            return false
        }
        activeSession = null
        activeOwner = null
        record("SESSION_STOPPED", "VPN engine session stopped and ownership released")
        publishState()
        return true
    }

    /**
     * Samples health only for the currently RUNNING session. Telemetry exceptions are
     * converted to an explicit invalid assessment; they never imply STOPPED or healthy.
     * Callers may schedule this at a bounded cadence from the app lifecycle monitor.
     */
    fun observeActiveTunnelHealth(): TunnelHealthAssessment? {
        // Never call an adapter while holding the controller monitor: native callbacks
        // can re-enter the controller while the adapter serializes backend operations.
        val capture = synchronized(this) {
            val owner = activeOwner
            val session = activeSession
            val snapshot = supervisor.snapshot()
            val provider = owner?.let { adaptersBySlot[it] as? TunnelHealthAwareAdapter }
            if (owner == null || session == null || provider == null ||
                snapshot.state != SessionState.RUNNING
            ) null else HealthSampleCapture(owner, session, snapshot.generation, provider)
        } ?: return null

        val assessment = try {
            capture.provider.runtimeHealth(nowEpochMillis())
        } catch (_: Exception) {
            TunnelHealthAssessment(
                state = TunnelHealthState.INVALID_TELEMETRY,
                explanation = "Runtime telemetry could not be read"
            )
        } ?: return null

        // Drop stale samples if disconnect/reconnect changed the owner or generation
        // while native telemetry was being read.
        synchronized(this) {
            val current = supervisor.snapshot()
            if (activeOwner != capture.owner || activeSession !== capture.session ||
                current.generation != capture.generation || current.state != SessionState.RUNNING
            ) return null
            record("TUNNEL_HEALTH_${assessment.state.name}", assessment.explanation)
        }
        return assessment
    }

    private data class HealthSampleCapture(
        val owner: EngineSlot,
        val session: EngineSession,
        val generation: Long,
        val provider: TunnelHealthAwareAdapter
    )


    /**
     * Consumes an OS network observation. Default-network validation is not proof of
     * VPN data-plane reachability. Degradation never silently stops the engine or TUN.
     */
    @Synchronized
    fun observeNetworkAssessment(assessment: NetworkAssessment): Boolean {
        val previous = lastNetworkAssessment
        lastNetworkAssessment = assessment
        val changed = previous == null ||
            previous.status != assessment.status ||
            previous.transport != assessment.transport ||
            previous.metered != assessment.metered
        if (!changed) return false
        val transport = assessment.transport?.name ?: "unknown"
        record("NETWORK_PATH_CHANGED", "status=${assessment.status};transport=$transport;metered=${assessment.metered}")
        if (activeSession != null && supervisor.snapshot().state == SessionState.RUNNING) {
            if (assessment.status == NetworkAssessmentStatus.VALIDATED) {
                record("SESSION_NETWORK_VALIDATED", "Android reports a validated default network; VPN reachability remains unverified")
            } else {
                record("SESSION_NETWORK_DEGRADED", "Default network is unavailable, unvalidated, stale, or unknown; TUN ownership retained")
            }
        }
        return true
    }


    /** Atomically reserves one bounded retry slot; concurrent callers cannot overlap. */
    @Synchronized
    fun beginRecoveryAttempt(): RecoveryAttemptToken? = recoveryAttemptGate.tryStart()

    /** Completes only the exact outstanding token; stale callbacks cannot unlock a newer attempt. */
    @Synchronized
    fun completeRecoveryAttempt(token: RecoveryAttemptToken, connected: Boolean): Boolean =
        recoveryAttemptGate.complete(token, connected)

    @Synchronized
    fun recoveryAttemptsStarted(): Int = recoveryAttemptGate.attemptsStarted()

    @Synchronized fun snapshot(): SessionSnapshot = supervisor.snapshot()
    @Synchronized fun latestPathDecision(): AdaptivePathDecision? = lastPathDecision

    /** Engine adapters/lifecycle observers call this when the active tunnel fails. */
    @Synchronized
    fun reportActiveEngineFailure(): Boolean {
        val owner = activeOwner ?: return false
        if (activeSession == null || supervisor.snapshot().state != SessionState.RUNNING) return false
        if (!supervisor.markFailed(owner)) return false
        record("ENGINE_SESSION_FAILED", "Active engine reported failure; exclusive ownership retained")
        publishState()
        return true
    }

    @Synchronized
    fun recoveryPlan(attempt: Int, maxAttempts: Int = ResilienceRecoveryCore.DEFAULT_MAX_ATTEMPTS): RecoveryPlan {
        // Never schedule a replacement while the previous engine still owns resources.
        if (activeSession != null) {
            return RecoveryPlan(
                RecoveryAction.NONE,
                attempt = attempt,
                reason = "Previous session cleanup must complete before recovery"
            )
        }
        val network = networkAssessmentProvider?.invoke() ?: NetworkAssessment(NetworkAssessmentStatus.UNKNOWN)
        return ResilienceRecoveryCore.plan(supervisor.snapshot().state, network, attempt, maxAttempts)
    }

    /**
     * Cleans a FAILED engine session before a caller schedules a replacement.
     * The returned plan is computed from the pre-cleanup FAILED state. Ownership is
     * released only after stop() succeeds; on failure the old owner remains exclusive.
     * A caller must honor delayMillis and call connect() only after fresh network policy
     * permits it. This method deliberately does not create a background scheduler.
     */
    @Synchronized
    fun prepareRecovery(
        attempt: Int,
        maxAttempts: Int = ResilienceRecoveryCore.DEFAULT_MAX_ATTEMPTS
    ): RecoveryPlan {
        val owner = activeOwner ?: return RecoveryPlan(
            RecoveryAction.NONE, attempt = attempt,
            reason = "No failed engine session is retained"
        )
        if (activeSession == null || supervisor.snapshot().state != SessionState.FAILED) {
            return RecoveryPlan(
                RecoveryAction.NONE, attempt = attempt,
                reason = "Only a retained FAILED session can be prepared for recovery"
            )
        }
        val network = networkAssessmentProvider?.invoke()
            ?: NetworkAssessment(NetworkAssessmentStatus.UNKNOWN)
        val plan = ResilienceRecoveryCore.plan(SessionState.FAILED, network, attempt, maxAttempts)
        if (!supervisor.beginStopping(owner)) {
            return RecoveryPlan(
                RecoveryAction.NONE, attempt = attempt,
                reason = "Session supervisor refused cleanup transition"
            )
        }
        try {
            activeSession?.stop()
        } catch (failure: Throwable) {
            supervisor.markFailed(owner)
            record("RECOVERY_CLEANUP_FAILED", "Previous engine still owns TUN resources")
            publishState()
            return RecoveryPlan(
                RecoveryAction.NONE, attempt = attempt,
                reason = "Previous session cleanup failed; ownership retained for retry"
            )
        }
        if (!supervisor.release(owner)) {
            supervisor.markFailed(owner)
            record("RECOVERY_RELEASE_PENDING", "Recovery cleanup returned but ownership release was not confirmed")
            publishState()
            return RecoveryPlan(
                RecoveryAction.NONE, attempt = attempt,
                reason = "Shared ownership remains held; cleanup handle retained for retry"
            )
        }
        activeSession = null
        activeOwner = null
        record("RECOVERY_CLEANUP_COMPLETE", "Previous engine released ownership before retry")
        publishState()
        return plan
    }

    private fun record(code: String, detail: String) {
        diagnostics?.record(code, detail, nowEpochMillis())
    }
}
