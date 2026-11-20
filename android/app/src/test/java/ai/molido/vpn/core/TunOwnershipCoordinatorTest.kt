package ai.molido.vpn.core

import org.junit.Assert.*
import org.junit.Test

class TunOwnershipCoordinatorTest {
    @Test
    fun concurrentEngineContentionAllowsExactlyOneLease() {
        val coordinator = TunOwnershipCoordinator()
        val first = coordinator.acquire(EngineSlot.WIREGUARD)
        val second = coordinator.acquire(EngineSlot.TOR)
        assertNotNull(first)
        assertNull(second)
        assertEquals(EngineSlot.WIREGUARD, coordinator.snapshot().owner)
    }

    @Test
    fun descriptorHandoffAndCleanupAckAreRequiredBeforeRelease() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquire(EngineSlot.V2RAY_XRAY)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 4102))
        assertTrue(coordinator.beginStop(lease))
        assertFalse(coordinator.confirmServiceTunClosed(lease))
        assertTrue(coordinator.acknowledgeEngineCleanup(lease, 4102))
        assertTrue(coordinator.confirmServiceTunClosed(lease))
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
        assertNotNull(coordinator.acquire(EngineSlot.WIREGUARD))
    }

    @Test
    fun processDeathRequiresServiceDescriptorCloseBeforeNextLease() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquire(EngineSlot.AMNEZIA_WG)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 5120))
        assertTrue(coordinator.reportEngineProcessDeath(lease, 5120))
        assertEquals(TunLeaseState.CLEANUP_REQUIRED, coordinator.snapshot().state)
        assertNull(coordinator.acquire(EngineSlot.WIREGUARD))
        assertTrue(coordinator.confirmServiceTunClosed(lease))
        assertNotNull(coordinator.acquire(EngineSlot.WIREGUARD))
    }

    @Test
    fun missingCleanupAckRetainsLeaseAndBlocksCompetingEngine() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquire(EngineSlot.WIREGUARD)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 6001))
        assertTrue(coordinator.beginStop(lease))
        // A timeout or failed cleanup call is not an acknowledgement.
        assertFalse(coordinator.confirmServiceTunClosed(lease))
        assertNull(coordinator.acquire(EngineSlot.TOR))
        assertEquals(TunLeaseState.STOPPING, coordinator.snapshot().state)
    }

    @Test
    fun wrongPidAndStaleGenerationCannotAcknowledgeOrRelease() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquire(EngineSlot.WIREGUARD)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 7001))
        assertTrue(coordinator.beginStop(lease))
        assertFalse(coordinator.acknowledgeEngineCleanup(lease, 7002))
        assertFalse(coordinator.confirmServiceTunClosed(TunLeaseToken(lease.engine, lease.generation + 1)))
        assertEquals(EngineSlot.WIREGUARD, coordinator.snapshot().owner)
    }

    @Test
    fun reservationCanBeAbortedOnlyBeforeDescriptorTransfer() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquire(EngineSlot.WIREGUARD)!!
        assertTrue(coordinator.confirmServiceTunClosed(lease))
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
    }

    @Test
    fun descriptorCannotBeReleasedWithoutAckOrProcessDeath() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquire(EngineSlot.PSIPHON)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 8001))
        assertTrue(coordinator.beginStop(lease))
        assertFalse(coordinator.confirmServiceTunClosed(lease))
        assertEquals(EngineSlot.PSIPHON, coordinator.snapshot().owner)
    }
}
