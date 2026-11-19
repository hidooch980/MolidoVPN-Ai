package ai.molido.vpn.core

/**
 * An adapter is an engine implementation, not a TUN owner.
 * Only VpnSessionController may invoke start/stop. Implementations must use the
 * application's one designated Android VPN service/TUN provider.
 * Configuration is sensitive and must never be logged or persisted by an adapter.
 */
interface EngineAdapter {
    val slot: EngineSlot
    fun start(configuration: String): EngineSession
}

/** A live engine session. stop() must be idempotent and release its engine resources. */
interface EngineSession {
    fun stop()
}

/**
 * Startup failed. A non-null cleanupSession means resources may still be active;
 * the controller must retain ownership and retry cleanup rather than release the slot.
 */
class EngineStartException(
    message: String,
    val cleanupSession: EngineSession?,
    cause: Throwable
) : Exception(message, cause)
