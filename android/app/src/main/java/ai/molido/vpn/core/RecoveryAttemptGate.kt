package ai.molido.vpn.core

/**
 * Process-local guard for bounded recovery orchestration.
 *
 * A token must be completed exactly once. Stale completions are rejected, one retry
 * may be in flight at a time, and only a confirmed successful reconnect resets the cap.
 */
data class RecoveryAttemptToken internal constructor(
    val generation: Long,
    val attempt: Int
)

class RecoveryAttemptGate(private val maxAttempts: Int = ResilienceRecoveryCore.DEFAULT_MAX_ATTEMPTS) {
    private var generation = 0L
    private var started = 0
    private var inFlight: RecoveryAttemptToken? = null

    init {
        require(maxAttempts > 0) { "Recovery attempt limit must be positive" }
    }

    @Synchronized
    fun tryStart(): RecoveryAttemptToken? {
        if (inFlight != null || started >= maxAttempts) return null
        generation += 1
        started += 1
        return RecoveryAttemptToken(generation, started).also { inFlight = it }
    }

    @Synchronized
    fun complete(token: RecoveryAttemptToken, connected: Boolean): Boolean {
        if (inFlight != token) return false
        inFlight = null
        if (connected) started = 0
        return true
    }

    @Synchronized
    fun attemptsStarted(): Int = started

    @Synchronized
    fun isInFlight(): Boolean = inFlight != null
}
