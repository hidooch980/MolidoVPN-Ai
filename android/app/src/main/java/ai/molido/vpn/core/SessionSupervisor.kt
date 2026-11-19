package ai.molido.vpn.core

/**
 * Single-session lifecycle coordinator.
 *
 * This class arbitrates engine ownership only. It does not create an Android TUN,
 * start packet I/O, or claim that a tunnel is connected.
 */
enum class SessionState {
    IDLE,
    RESERVED,
    RUNNING,
    STOPPING,
    FAILED
}

data class SessionSnapshot(
    val owner: EngineSlot?,
    val state: SessionState,
    val generation: Long
)

class SessionSupervisor {
    private var activeEngine: EngineSlot? = null
    private var state: SessionState = SessionState.IDLE
    private var generation: Long = 0

    /** Reserves the single session slot for an engine. */
    @Synchronized
    fun claim(engine: EngineSlot): Boolean {
        if (state != SessionState.IDLE) {
            return activeEngine == engine &&
                (state == SessionState.RESERVED || state == SessionState.RUNNING)
        }
        activeEngine = engine
        state = SessionState.RESERVED
        generation += 1
        return true
    }

    /** Marks startup complete; callers must only do this after the data plane is ready. */
    @Synchronized
    fun markRunning(engine: EngineSlot): Boolean {
        if (activeEngine != engine || state != SessionState.RESERVED) return false
        state = SessionState.RUNNING
        return true
    }

    /** Moves an owned session into an explicit shutdown phase. */
    @Synchronized
    fun beginStopping(engine: EngineSlot): Boolean {
        if (activeEngine != engine) return false
        if (state != SessionState.RESERVED &&
            state != SessionState.RUNNING &&
            state != SessionState.FAILED
        ) return false
        state = SessionState.STOPPING
        return true
    }

    /** Records a fail-closed error while retaining ownership until cleanup completes. */
    @Synchronized
    fun markFailed(engine: EngineSlot): Boolean {
        if (activeEngine != engine || state == SessionState.IDLE) return false
        state = SessionState.FAILED
        return true
    }

    /**
     * Releases ownership only before startup or after the caller has entered STOPPING.
     * A RUNNING session cannot be silently released.
     */
    @Synchronized
    fun release(engine: EngineSlot): Boolean {
        if (activeEngine != engine) return false
        if (state != SessionState.RESERVED && state != SessionState.STOPPING) return false
        activeEngine = null
        state = SessionState.IDLE
        return true
    }

    @Synchronized
    fun currentOwner(): EngineSlot? = activeEngine

    @Synchronized
    fun snapshot(): SessionSnapshot = SessionSnapshot(activeEngine, state, generation)
}
