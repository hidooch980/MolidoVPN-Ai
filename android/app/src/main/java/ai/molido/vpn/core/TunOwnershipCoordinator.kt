package ai.molido.vpn.core

/**
 * Exclusive lease for the application's one Android VPN/TUN provider.
 *
 * WireGuard currently uses the upstream GoBackend.VpnService, which owns TUN creation
 * and closes the userspace tunnel through Backend.setState(DOWN). Future isolated engines
 * must use the descriptor-handoff protocol and the same coordinator instance.
 *
 * A timeout is never proof of cleanup. A lease remains held until the active transport
 * explicitly confirms cleanup.
 */
enum class TunLeaseState {
    IDLE,
    RESERVED,
    ACTIVE,
    STOPPING,
    CLEANUP_REQUIRED
}

enum class TunLeaseTransport {
    BACKEND_MANAGED,
    DESCRIPTOR_HANDOFF
}

data class TunLeaseToken(
    val engine: EngineSlot,
    val generation: Long,
    val transport: TunLeaseTransport = TunLeaseTransport.DESCRIPTOR_HANDOFF
)

data class TunLeaseSnapshot(
    val owner: EngineSlot?,
    val generation: Long,
    val state: TunLeaseState,
    val transport: TunLeaseTransport?,
    val enginePid: Int?,
    val descriptorTransferred: Boolean,
    val cleanupAcknowledged: Boolean,
    val engineProcessDead: Boolean
)

class TunOwnershipCoordinator {
    private var generation = 0L
    private var owner: EngineSlot? = null
    private var state = TunLeaseState.IDLE
    private var transport: TunLeaseTransport? = null
    private var enginePid: Int? = null
    private var descriptorTransferred = false
    private var cleanupAcknowledged = false
    private var engineProcessDead = false

    /** Reserve a lease for a future isolated engine descriptor handoff. */
    @Synchronized
    fun acquire(engine: EngineSlot): TunLeaseToken? =
        acquire(engine, TunLeaseTransport.DESCRIPTOR_HANDOFF)

    /** Reserve the existing upstream VPN backend's single managed TUN lifecycle. */
    @Synchronized
    fun acquireBackendManaged(engine: EngineSlot): TunLeaseToken? =
        acquire(engine, TunLeaseTransport.BACKEND_MANAGED)

    private fun acquire(engine: EngineSlot, leaseTransport: TunLeaseTransport): TunLeaseToken? {
        if (state != TunLeaseState.IDLE) return null
        generation += 1
        owner = engine
        state = TunLeaseState.RESERVED
        transport = leaseTransport
        enginePid = null
        descriptorTransferred = false
        cleanupAcknowledged = false
        engineProcessDead = false
        return TunLeaseToken(engine, generation, leaseTransport)
    }

    /** Backend reports UP only after its setState(UP) call has completed successfully. */
    @Synchronized
    fun markBackendActive(token: TunLeaseToken): Boolean {
        if (!matches(token) || token.transport != TunLeaseTransport.BACKEND_MANAGED ||
            state != TunLeaseState.RESERVED
        ) return false
        state = TunLeaseState.ACTIVE
        return true
    }

    /** Records a successful FD handoff to the one reserved isolated engine process. */
    @Synchronized
    fun markDescriptorTransferred(token: TunLeaseToken, pid: Int): Boolean {
        if (!matches(token) || token.transport != TunLeaseTransport.DESCRIPTOR_HANDOFF ||
            state != TunLeaseState.RESERVED || pid <= 0
        ) return false
        enginePid = pid
        descriptorTransferred = true
        state = TunLeaseState.ACTIVE
        return true
    }

    @Synchronized
    fun beginStop(token: TunLeaseToken): Boolean {
        if (!matches(token)) return false
        if (state == TunLeaseState.STOPPING) return true
        val canStop = state == TunLeaseState.ACTIVE ||
            (transport == TunLeaseTransport.BACKEND_MANAGED && state == TunLeaseState.RESERVED)
        if (!canStop) return false
        state = TunLeaseState.STOPPING
        return true
    }

    /** Backend-managed cleanup acknowledgement after setState(DOWN) and getState(DOWN). */
    @Synchronized
    fun confirmBackendStopped(token: TunLeaseToken): Boolean {
        if (!matches(token) || token.transport != TunLeaseTransport.BACKEND_MANAGED ||
            state != TunLeaseState.STOPPING
        ) return false
        releaseLease()
        return true
    }

    /** The isolated engine must send this only after stopping and closing its FD copy. */
    @Synchronized
    fun acknowledgeEngineCleanup(token: TunLeaseToken, pid: Int): Boolean {
        if (!matches(token) || token.transport != TunLeaseTransport.DESCRIPTOR_HANDOFF ||
            state != TunLeaseState.STOPPING || enginePid != pid ||
            !descriptorTransferred || engineProcessDead
        ) return false
        cleanupAcknowledged = true
        return true
    }

    /**
     * Binder death/process death is not itself permission to start another engine.
     * The service must still close its own descriptor before releasing the lease.
     */
    @Synchronized
    fun reportEngineProcessDeath(token: TunLeaseToken, pid: Int): Boolean {
        if (!matches(token) || token.transport != TunLeaseTransport.DESCRIPTOR_HANDOFF ||
            enginePid != pid ||
            (state != TunLeaseState.ACTIVE && state != TunLeaseState.STOPPING)
        ) return false
        engineProcessDead = true
        state = TunLeaseState.CLEANUP_REQUIRED
        return true
    }

    /** Central VpnService calls this only after closing its own TUN descriptor. */
    @Synchronized
    fun confirmServiceTunClosed(token: TunLeaseToken): Boolean {
        if (!matches(token) || token.transport != TunLeaseTransport.DESCRIPTOR_HANDOFF) return false
        val safeToRelease = when (state) {
            TunLeaseState.RESERVED -> !descriptorTransferred
            TunLeaseState.STOPPING -> cleanupAcknowledged
            TunLeaseState.CLEANUP_REQUIRED -> engineProcessDead
            else -> false
        }
        if (!safeToRelease) return false
        releaseLease()
        return true
    }

    @Synchronized
    fun snapshot(): TunLeaseSnapshot = TunLeaseSnapshot(
        owner = owner,
        generation = generation,
        state = state,
        transport = transport,
        enginePid = enginePid,
        descriptorTransferred = descriptorTransferred,
        cleanupAcknowledged = cleanupAcknowledged,
        engineProcessDead = engineProcessDead
    )

    private fun releaseLease() {
        owner = null
        state = TunLeaseState.IDLE
        transport = null
        enginePid = null
        descriptorTransferred = false
        cleanupAcknowledged = false
        engineProcessDead = false
    }

    private fun matches(token: TunLeaseToken): Boolean =
        owner == token.engine && generation == token.generation && transport == token.transport
}