package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchitectureInventoryTest {
    @Test
    fun containsExactlyTheSixApprovedEngineSlots() {
        assertEquals(
            setOf(
                EngineSlot.WIREGUARD,
                EngineSlot.AMNEZIA_WG,
                EngineSlot.V2RAY_XRAY,
                EngineSlot.PSIPHON,
                EngineSlot.TOR,
                EngineSlot.MASQUE_WARP
            ),
            ArchitectureInventory.engines
        )
    }

    @Test
    fun containsExactlyTheEightApprovedComplementaryCores() {
        assertEquals(
            setOf(
                PlatformCore.SESSION_TUN_SUPERVISOR,
                PlatformCore.NETWORK_INTELLIGENCE,
                PlatformCore.ADAPTIVE_PATH_POLICY,
                PlatformCore.SECURITY_PRIVACY,
                PlatformCore.RESILIENCE_RECOVERY,
                PlatformCore.DIAGNOSTICS_OBSERVABILITY,
                PlatformCore.CONTROL_PLANE_CONFIGURATION,
                PlatformCore.UPDATE_INTEGRITY
            ),
            ArchitectureInventory.cores
        )
    }
}
