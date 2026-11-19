package ai.molido.vpn.core

import org.junit.Assert.*
import org.junit.Test

class SessionSupervisorTest {
    @Test
    fun onlyOneEngineCanOwnSessionAtATime() {
        val supervisor = SessionSupervisor()
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertFalse(supervisor.claim(EngineSlot.TOR))
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
    }

    @Test
    fun ownerCanReleaseAndNextEngineCanClaim() {
        val supervisor = SessionSupervisor()
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertTrue(supervisor.release(EngineSlot.WIREGUARD))
        assertTrue(supervisor.claim(EngineSlot.AMNEZIA_WG))
        assertEquals(EngineSlot.AMNEZIA_WG, supervisor.currentOwner())
    }

    @Test
    fun nonOwnerCannotReleaseSession() {
        val supervisor = SessionSupervisor()
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertFalse(supervisor.release(EngineSlot.PSIPHON))
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
    }
}
