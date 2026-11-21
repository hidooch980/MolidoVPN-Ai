package ai.molido.vpn.core

/**
 * Single-session lifecycle coordinator and, when configured, the authority that reserves
 * the application's shared TUN lease. It never creates a TUN or starts packet I/O.
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

class SessionSupervisor(
    private val tunOwnership: TunOwnershipCoordinator? = null
) {
    private var activeEngine: EngineSlot? = null
    private var activeLease: TunLeaseToken? = null
    private var state: SessionState = SessionState.IDLE
    private var generation: Long = 0

    /** Reserves the one session slot and, in production, its matching shared TUN lease. */
    @Synchronized
    fun claim(engine: EngineSlot): Boolean {
        if (state != SessionState.IDLE) return false
        val lease = tunOwnership?.let { coordinator ->
            if (engine == EngineSlot.WIREGUARD) {
                coordinator.acquireBackendManaged(engine)
            } else {
                coordinator.acquire(engine)
            } ?: return false
        }
        activeEngine = engine
        activeLease = lease
        state = SessionState.RESERVED
        generation += 1
        return true
    }

    @Synchronized
    fun requiresTunLease(): Boolean = tunOwnership != null

    /** Returns only the exact generation reserved for the current owner. */
    @Synchronized
    fun tunLeaseFor(engine: EngineSlot): TunLeaseToken? =
        if (activeEngine == engine) activeLease else null

    /** Marks startup complete only after the adapter has activated the same TUN lease. */
    @Synchronized
    fun markRunning(engine: EngineSlot): Boolean {
        if (activeEngine != engine || state != SessionState.RESERVED) return false
        val lease = activeLease
        if (tunOwnership != null) {
            if (lease == null) return false
            val snapshot = tunOwnership.snapshot()
            if (snapshot.owner != engine ||
                snapshot.generation != lease.generation ||
                snapshot.state != TunLeaseState.ACTIVE
            ) return false
        }
        state = SessionState.RUNNING
        return true
    }

    /** Moves both lifecycle authorities into STOPPING; repeated transitions are safe. */
    @Synchronized
    fun beginStopping(engine: EngineSlot): Boolean {
        if (activeEngine != engine) return false
        if (state != SessionState.RESERVED &&
            state != SessionState.RUNNING &&
            state != SessionState.FAILED
        ) return false
        val lease = activeLease
        if (tunOwnership != null && (lease == null || !tunOwnership.beginStop(lease))) return false
        state = SessionState.STOPPING
        return true
    }

    /** Records a fail-closed error while retaining both ownership records. */
    @Synchronized
    fun markFailed(engine: EngineSlot): Boolean {
        if (activeEngine != engine || state == SessionState.IDLE) return false
        state = SessionState.FAILED
        return true
    }

    /**
     * Releases only after the transport itself has confirmed cleanup. A RUNNING or
     * still-leased session can never be silently released.
     */
    @Synchronized
    fun release(engine: EngineSlot): Boolean {
        if (activeEngine != engine) return false
        if (state != SessionState.RESERVED && state != SessionState.STOPPING) return false
        if (tunOwnership != null) {
            val lease = activeLease ?: return false
            val snapshot = tunOwnership.snapshot()
            if (snapshot.owner != null || snapshot.state != TunLeaseState.IDLE ||
                snapshot.generation != lease.generation
            ) return false
        }
        activeEngine = null
        activeLease = null
        state = SessionState.IDLE
        return true
    }

    /**
     * Roll back a startup that failed before returning a session handle. This is allowed
     * only while the exact lease is still RESERVED; an activated/uncertain TUN is retained.
     */
    @Synchronized
    fun releaseUnstartedReservation(engine: EngineSlot): Boolean {
        if (activeEngine != engine || state != SessionState.FAILED) return false
        val coordinator = tunOwnership
        val lease = activeLease
        if (coordinator != null) {
            if (lease == null) return false
            val snapshot = coordinator.snapshot()
            val alreadyCleanedByAdapter =
                snapshot.owner == null &&
                snapshot.state == TunLeaseState.IDLE &&
                snapshot.generation == lease.generation
            val released = if (alreadyCleanedByAdapter) {
                true
            } else {
                if (!coordinator.isReserved(lease)) return false
                when (lease.transport) {
                    TunLeaseTransport.BACKEND_MANAGED ->
                        coordinator.beginStop(lease) && coordinator.confirmBackendStopped(lease)
                    TunLeaseTransport.DESCRIPTOR_HANDOFF ->
                        coordinator.confirmServiceTunClosed(lease)
                }
            }
            if (!released) return false
        }
        activeEngine = null
        activeLease = null
        state = SessionState.IDLE
        return true
    }

    @Synchronized
    fun currentOwner(): EngineSlot? = activeEngine

    @Synchronized
    fun snapshot(): SessionSnapshot = SessionSnapshot(activeEngine, state, generation)
}
