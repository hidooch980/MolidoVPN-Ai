package ai.molido.vpn.core

/**
 * In-process arbitration contract for the one active engine session.
 *
 * This is not a packet data plane and does not establish Android TUN.
 * Platform service integration and engine-specific packet I/O remain unimplemented.
 */
class SessionSupervisor {
    private var activeEngine: EngineSlot? = null

    @Synchronized
    fun claim(engine: EngineSlot): Boolean {
        if (activeEngine != null) return activeEngine == engine
        activeEngine = engine
        return true
    }

    @Synchronized
    fun release(engine: EngineSlot): Boolean {
        if (activeEngine != engine) return false
        activeEngine = null
        return true
    }

    @Synchronized
    fun currentOwner(): EngineSlot? = activeEngine
}
