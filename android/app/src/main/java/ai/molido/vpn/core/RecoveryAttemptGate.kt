package ai.molido.vpn.core

data class RecoveryAttemptToken internal constructor(
    val generation: Long,
    val attempt: Int
)

data class PersistedRecoveryAttemptState(
    val attemptsStarted: Int = 0,
    val generation: Long = 0,
    val inFlight: Boolean = false,
    val interrupted: Boolean = false
)

/** Storage must atomically persist the complete state before returning true. */
interface RecoveryAttemptStateStore {
    fun read(): PersistedRecoveryAttemptState
    fun write(state: PersistedRecoveryAttemptState): Boolean
}

class RecoveryAttemptGate(
    private val maxAttempts: Int = ResilienceRecoveryCore.DEFAULT_MAX_ATTEMPTS,
    private val store: RecoveryAttemptStateStore? = null
) {
    private var generation = 0L
    private var started = 0
    private var inFlight: RecoveryAttemptToken? = null
    private var interrupted = false

    init {
        require(maxAttempts > 0) { "Recovery attempt limit must be positive" }
        val persisted = runCatching { store?.read() ?: PersistedRecoveryAttemptState() }
            .getOrElse { PersistedRecoveryAttemptState(attemptsStarted = maxAttempts, interrupted = true) }
        generation = persisted.generation.coerceAtLeast(0)
        started = persisted.attemptsStarted.coerceIn(0, maxAttempts)
        interrupted = persisted.interrupted
        // A process died after durably reserving a retry. Do not replay it after restart:
        // mark this recovery cycle exhausted until an explicit operator reset.
        if (persisted.inFlight) {
            started = maxAttempts
            interrupted = true
            runCatching {
                store?.write(PersistedRecoveryAttemptState(started, generation, false, true))
            }
        }
        if (interrupted) started = maxAttempts
    }

    @Synchronized
    fun tryStart(): RecoveryAttemptToken? {
        if (interrupted || inFlight != null || started >= maxAttempts) return null
        val token = RecoveryAttemptToken(generation + 1, started + 1)
        val next = PersistedRecoveryAttemptState(token.attempt, token.generation, true, false)
        if (store?.let { runCatching { it.write(next) }.getOrDefault(false) } == false) return null
        generation = token.generation
        started = token.attempt
        inFlight = token
        return token
    }

    @Synchronized
    fun complete(token: RecoveryAttemptToken, connected: Boolean): Boolean {
        if (inFlight != token) return false
        val nextStarted = if (connected) 0 else started
        val next = PersistedRecoveryAttemptState(nextStarted, generation, false, false)
        if (store?.let { runCatching { it.write(next) }.getOrDefault(false) } == false) return false
        inFlight = null
        started = nextStarted
        return true
    }

    /** Only an explicit user/operator action may clear a process-death recovery lockout. */
    @Synchronized
    fun resetAfterExplicitUserAction(): Boolean {
        val next = PersistedRecoveryAttemptState(
            attemptsStarted = 0,
            generation = generation,
            inFlight = false,
            interrupted = false
        )
        if (store?.let { runCatching { it.write(next) }.getOrDefault(false) } == false) return false
        // Keep generations monotonic so a late callback from before reset cannot
        // accidentally match a newly issued token.
        started = 0
        inFlight = null
        interrupted = false
        return true
    }

    @Synchronized fun attemptsStarted(): Int = started
    @Synchronized fun isInFlight(): Boolean = inFlight != null
    @Synchronized fun isInterrupted(): Boolean = interrupted
}
