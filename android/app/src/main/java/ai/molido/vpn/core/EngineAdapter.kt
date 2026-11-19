package ai.molido.vpn.core

/**
 * Packet I/O supplied by the platform VPN service after it establishes the one TUN.
 * Engine adapters consume this interface; they must never create or own Android TUNs.
 */
interface PacketIo {
    /** Reads one IP packet into [buffer], returning its byte count or -1 when closed. */
    fun readPacket(buffer: ByteArray): Int

    /** Writes one complete IP packet from [packet] using [length] bytes. */
    fun writePacket(packet: ByteArray, length: Int)
}

/** Handle for an engine data plane started by an adapter. */
interface EngineSession {
    /** Stops engine workers and releases engine-specific resources, not the Android TUN. */
    fun stop()
}

/**
 * Contract for one of the six approved engine slots.
 * Implementations receive platform-owned packet I/O and may not call VpnService.Builder.
 */
interface EngineAdapter {
    val slot: EngineSlot

    /** Starts the engine data plane against the PacketIo owned by MolidoVpnService. */
    fun start(packetIo: PacketIo): EngineSession
}
