package ai.molido.vpn.core

/**
 * The only application entry point allowed to start or stop VPN engine adapters.
 * Call from a worker thread: engine startup can perform DNS resolution and native setup.
 */
class VpnSessionController(
    private val supervisor: SessionSupervisor,
    adapters: Collection<EngineAdapter>
) {
    private val adaptersBySlot: Map<EngineSlot, EngineAdapter> =
        adapters.associateBy { it.slot }.also { mapped ->
            require(mapped.size == adapters.size) { "Duplicate engine adapter slot" }
        }

    private var activeSession: EngineSession? = null
    private var activeOwner: EngineSlot? = null

    @Synchronized
    fun connect(slot: EngineSlot, configuration: String): Boolean {
        if (activeSession != null) return activeOwner == slot
        val adapter = adaptersBySlot[slot] ?: return false
        if (!supervisor.claim(slot)) return false

        try {
            val session = adapter.start(configuration)
            if (!supervisor.markRunning(slot)) {
                supervisor.markFailed(slot)
                supervisor.beginStopping(slot)
                session.stop()
                supervisor.release(slot)
                return false
            }
            activeSession = session
            activeOwner = slot
            return true
        } catch (failure: Exception) {
            // Keep the ownership gate closed until the failed startup is unwound.
            supervisor.markFailed(slot)
            supervisor.beginStopping(slot)
            supervisor.release(slot)
            throw failure
        }
    }

    @Synchronized
    fun disconnect(): Boolean {
        val owner = activeOwner ?: return supervisor.snapshot().state == SessionState.IDLE
        if (!supervisor.beginStopping(owner)) return false
        try {
            activeSession?.stop()
        } catch (failure: Exception) {
            // Retain the owner in FAILED state; a later disconnect may retry cleanup.
            supervisor.markFailed(owner)
            throw failure
        }
        activeSession = null
        activeOwner = null
        check(supervisor.release(owner)) { "Session ownership could not be released after cleanup" }
        return true
    }

    @Synchronized
    fun snapshot(): SessionSnapshot = supervisor.snapshot()
}
