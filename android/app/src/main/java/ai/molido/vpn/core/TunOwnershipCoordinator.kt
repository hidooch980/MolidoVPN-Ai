package ai.molido.vpn.core

/**
 * Process-independent lease state for the single Android TUN owner.
 *
 * This class is deliberately platform-neutral: it does not create, duplicate, or close
 * ParcelFileDescriptors. The future central VpnService must call [confirmServiceTunClosed]
 * only after it has closed its TUN descriptor and has either received the engine cleanup
 * acknowledgement or observed the isolated engine process death (which closes that
 * process's duplicated descriptors at the OS level).
 *
 * A timeout is never proof of cleanup. If neither acknowledgement nor process death is
 * observed, the lease remains held and all competing engines are rejected.
 */
enum class TunLeaseState {
    IDLE,
    RESERVED,
    ACTIVE,
    STOPPING,
    CLEANUP_REQUIRED
}

data class TunLeaseToken(
    val engine: EngineSlot,
    val generation: Long
)

data class TunLeaseSnapshot(
    val owner: EngineSlot?,
    val generation: Long,
    val state: TunLeaseState,
    val enginePid: Int?,
    val descriptorTransferred: Boolean,
    val cleanupAcknowledged: Boolean,
    val engineProcessDead: Boolean
)

class TunOwnershipCoordinator {
    private var generation = 0L
    private var owner: EngineSlot? = null
    private var state = TunLeaseState.IDLE
    private var enginePid: Int? = null
    private var descriptorTransferred = false
    private var cleanupAcknowledged = false
    private var engineProcessDead = false

    @Synchronized
    fun acquire(engine: EngineSlot): TunLeaseToken? {
        if (state != TunLeaseState.IDLE) return null
        generation += 1
        owner = engine
        state = TunLeaseState.RESERVED
        enginePid = null
        descriptorTransferred = false
        cleanupAcknowledged = false
        engineProcessDead = false
        return TunLeaseToken(engine, generation)
    }

    /** Records a successful FD handoff to the one reserved engine process. */
    @Synchronized
    fun markDescriptorTransferred(token: TunLeaseToken, pid: Int): Boolean {
        if (!matches(token) || state != TunLeaseState.RESERVED || pid <= 0) return false
        enginePid = pid
        descriptorTransferred = true
        state = TunLeaseState.ACTIVE
        return true
    }

    @Synchronized
    fun beginStop(token: TunLeaseToken): Boolean {
        if (!matches(token) || state != TunLeaseState.ACTIVE) return false
        state = TunLeaseState.STOPPING
        return true
    }

    /** The isolated engine must send this only after stopping and closing its FD copy. */
    @Synchronized
    fun acknowledgeEngineCleanup(token: TunLeaseToken, pid: Int): Boolean {
        if (!matches(token) || state != TunLeaseState.STOPPING ||
            enginePid != pid || !descriptorTransferred || engineProcessDead
        ) return false
        cleanupAcknowledged = true
        return true
    }

    /**
     * Binder death/process death is not itself permission to start another engine.
     * It changes the lease to CLEANUP_REQUIRED; the service must still close its own
     * descriptor and call [confirmServiceTunClosed].
     */
    @Synchronized
    fun reportEngineProcessDeath(token: TunLeaseToken, pid: Int): Boolean {
        if (!matches(token) || enginePid != pid ||
            (state != TunLeaseState.ACTIVE && state != TunLeaseState.STOPPING)
        ) return false
        engineProcessDead = true
        state = TunLeaseState.CLEANUP_REQUIRED
        return true
    }

    /**
     * Called by the central VpnService only after its TUN descriptor is actually closed.
     * The engine-side descriptor must already be closed by acknowledgement or process death.
     */
    @Synchronized
    fun confirmServiceTunClosed(token: TunLeaseToken): Boolean {
        if (!matches(token)) return false
        val safeToRelease = when (state) {
            TunLeaseState.RESERVED -> !descriptorTransferred
            TunLeaseState.STOPPING -> cleanupAcknowledged
            TunLeaseState.CLEANUP_REQUIRED -> engineProcessDead
            else -> false
        }
        if (!safeToRelease) return false
        owner = null
        state = TunLeaseState.IDLE
        enginePid = null
        descriptorTransferred = false
        cleanupAcknowledged = false
        engineProcessDead = false
        return true
    }

    @Synchronized
    fun snapshot(): TunLeaseSnapshot = TunLeaseSnapshot(
        owner = owner,
        generation = generation,
        state = state,
        enginePid = enginePid,
        descriptorTransferred = descriptorTransferred,
        cleanupAcknowledged = cleanupAcknowledged,
        engineProcessDead = engineProcessDead
    )

    private fun matches(token: TunLeaseToken): Boolean =
        owner == token.engine && generation == token.generation
}
