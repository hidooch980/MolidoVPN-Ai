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
        assertEquals(SessionState.RESERVED, supervisor.snapshot().state)
    }

    @Test
    fun ownerCanReleaseReservationAndNextEngineCanClaim() {
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

    @Test
    fun runningSessionCannotBeReleasedWithoutStopping() {
        val supervisor = SessionSupervisor()
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertTrue(supervisor.markRunning(EngineSlot.WIREGUARD))
        assertFalse(supervisor.release(EngineSlot.WIREGUARD))
        assertEquals(SessionState.RUNNING, supervisor.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
    }

    @Test
    fun stoppingMustCompleteBeforeOwnershipCanBeReleased() {
        val supervisor = SessionSupervisor()
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertTrue(supervisor.markRunning(EngineSlot.WIREGUARD))
        assertTrue(supervisor.beginStopping(EngineSlot.WIREGUARD))
        assertTrue(supervisor.release(EngineSlot.WIREGUARD))
        assertNull(supervisor.currentOwner())
        assertEquals(SessionState.IDLE, supervisor.snapshot().state)
    }

    @Test
    fun failedSessionRetainsOwnershipUntilCleanup() {
        val supervisor = SessionSupervisor()
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertTrue(supervisor.markRunning(EngineSlot.WIREGUARD))
        assertTrue(supervisor.markFailed(EngineSlot.WIREGUARD))
        assertFalse(supervisor.claim(EngineSlot.TOR))
        assertTrue(supervisor.beginStopping(EngineSlot.WIREGUARD))
        assertTrue(supervisor.release(EngineSlot.WIREGUARD))
    }

    @Test
    fun generationIncrementsForEachNewReservation() {
        val supervisor = SessionSupervisor()
        assertEquals(0L, supervisor.snapshot().generation)
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertEquals(1L, supervisor.snapshot().generation)
        assertTrue(supervisor.release(EngineSlot.WIREGUARD))
        assertTrue(supervisor.claim(EngineSlot.TOR))
        assertEquals(2L, supervisor.snapshot().generation)
    }
}
