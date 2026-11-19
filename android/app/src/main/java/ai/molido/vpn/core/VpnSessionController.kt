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

        val session = try {
            adapter.start(configuration)
        } catch (failure: Throwable) {
            supervisor.markFailed(slot)
            val cleanupSession = (failure as? EngineStartException)?.cleanupSession
            if (cleanupSession != null) {
                activeSession = cleanupSession
                activeOwner = slot
            } else {
                // A plain adapter startup failure contractually means rollback completed.
                supervisor.beginStopping(slot)
                supervisor.release(slot)
            }
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
                throw failure
            }
            check(supervisor.release(slot)) { "Session ownership could not be released after startup rollback" }
            return false
        }

        activeSession = session
        activeOwner = slot
        return true
    }

    @Synchronized
    fun disconnect(): Boolean {
        val owner = activeOwner ?: return supervisor.snapshot().state == SessionState.IDLE
        if (!supervisor.beginStopping(owner)) return false
        try {
            activeSession?.stop()
        } catch (failure: Throwable) {
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
