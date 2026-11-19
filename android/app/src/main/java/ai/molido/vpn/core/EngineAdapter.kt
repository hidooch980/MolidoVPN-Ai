package ai.molido.vpn.core

/**
 * An adapter is an engine implementation, not a TUN owner.
 *
 * Only VpnSessionController may invoke start/stop. Each implementation must use the
 * application's one designated Android VPN service and must never register or establish
 * a second VpnService/TUN. Configuration is sensitive and must never be logged or persisted
 * by an adapter.
 */
interface EngineAdapter {
    val slot: EngineSlot
    fun start(configuration: String): EngineSession
}

/** A live engine session. stop() must be idempotent and release its engine resources. */
interface EngineSession {
    fun stop()
}
