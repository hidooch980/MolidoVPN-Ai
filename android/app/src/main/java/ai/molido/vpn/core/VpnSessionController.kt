package ai.molido.vpn.core

class VpnSessionController(
    private val supervisor: SessionSupervisor,
    adapters: Collection<EngineAdapter>,
    private val networkAssessmentProvider: (() -> NetworkAssessment)? = null,
    private val networkPolicyProvider: () -> NetworkPolicy = { NetworkPolicy.ANY_VALIDATED },
    private val diagnostics: DiagnosticsObservabilityCore? = null,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
) {
    private val adaptersBySlot: Map<EngineSlot, EngineAdapter> =
        adapters.associateBy { it.slot }.also { mapped ->
            require(mapped.size == adapters.size) { "Duplicate engine adapter slot" }
        }
    private var activeSession: EngineSession? = null
    private var activeOwner: EngineSlot? = null
    private var lastPathDecision: AdaptivePathDecision? = null

    @Synchronized
    fun connect(slot: EngineSlot, configuration: String): Boolean {
        if (activeSession != null) return activeOwner == slot && supervisor.snapshot().state == SessionState.RUNNING
        val adapter = adaptersBySlot[slot] ?: return false
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
        val session = try {
            adapter.start(configuration)
        } catch (failure: Throwable) {
            supervisor.markFailed(slot)
            val cleanupSession = (failure as? EngineStartException)?.cleanupSession
            if (cleanupSession != null) {
                activeSession = cleanupSession
                activeOwner = slot
            } else {
                supervisor.beginStopping(slot)
                supervisor.release(slot)
            }
            record("ENGINE_START_FAILED", "Engine startup failed; sensitive details omitted")
            throw failure
        }
        if (!supervisor.markRunning(slot)) {
            supervisor.markFailed(slot)
            supervisor.beginStopping(slot)
            try {
                session.stop()
            } catch (failure: Throwable) {
                activeSession = session
                activeOwner = slot
                supervisor.markFailed(slot)
                record("START_ROLLBACK_PENDING", "Startup rollback requires cleanup retry")
                throw failure
            }
            check(supervisor.release(slot)) { "Session ownership could not be released after startup rollback" }
            record("START_ROLLBACK_COMPLETE", "Session startup rolled back")
            return false
        }
        activeSession = session
        activeOwner = slot
        record("SESSION_STARTED", "VPN engine session entered RUNNING state")
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
            throw failure
        }
        activeSession = null
        activeOwner = null
        check(supervisor.release(owner)) { "Session ownership could not be released after cleanup" }
        record("SESSION_STOPPED", "VPN engine session stopped and ownership released")
        return true
    }

    @Synchronized fun snapshot(): SessionSnapshot = supervisor.snapshot()
    @Synchronized fun latestPathDecision(): AdaptivePathDecision? = lastPathDecision

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
            return RecoveryPlan(
                RecoveryAction.NONE, attempt = attempt,
                reason = "Previous session cleanup failed; ownership retained for retry"
            )
        }
        activeSession = null
        activeOwner = null
        check(supervisor.release(owner)) { "Session ownership could not be released after recovery cleanup" }
        record("RECOVERY_CLEANUP_COMPLETE", "Previous engine released ownership before retry")
        return plan
    }

    private fun record(code: String, detail: String) {
        diagnostics?.record(code, detail, nowEpochMillis())
    }
}
