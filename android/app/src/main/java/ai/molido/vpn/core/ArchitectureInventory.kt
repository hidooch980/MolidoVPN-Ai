package ai.molido.vpn.core

/** The six approved engine slots. Adding a seventh slot is an architecture change and is prohibited. */
enum class EngineSlot {
    WIREGUARD,
    AMNEZIA_WG,
    V2RAY_XRAY,
    PSIPHON,
    TOR,
    MASQUE_WARP
}

/** The eight complementary cores; these are not VPN engines. */
enum class PlatformCore {
    SESSION_TUN_SUPERVISOR,
    NETWORK_INTELLIGENCE,
    ADAPTIVE_PATH_POLICY,
    SECURITY_PRIVACY,
    RESILIENCE_RECOVERY,
    DIAGNOSTICS_OBSERVABILITY,
    CONTROL_PLANE_CONFIGURATION,
    UPDATE_INTEGRITY
}

object ArchitectureInventory {
    val engines: Set<EngineSlot> = EngineSlot.entries.toSet()
    val cores: Set<PlatformCore> = PlatformCore.entries.toSet()

    init {
        require(engines.size == 6) { "MolidoVPN AI must contain exactly six engine slots" }
        require(cores.size == 8) { "MolidoVPN AI must contain exactly eight complementary cores" }
    }
}
