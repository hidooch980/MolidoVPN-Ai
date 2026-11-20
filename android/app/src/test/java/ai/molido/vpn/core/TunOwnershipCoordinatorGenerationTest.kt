package ai.molido.vpn.core

import org.junit.Assert.*
import org.junit.Test

/** Regression tests for generation-scoped TUN lease capabilities. */
class TunOwnershipCoordinatorGenerationTest {
    @Test
    fun staleBackendTokenCannotAffectLaterLeaseForSameEngine() {
        val coordinator = TunOwnershipCoordinator()
        val old = coordinator.acquireBackendManaged(EngineSlot.WIREGUARD)!!
        assertTrue(coordinator.beginStop(old))
        assertTrue(coordinator.confirmBackendStopped(old))

        val current = coordinator.acquireBackendManaged(EngineSlot.WIREGUARD)!!
        assertNotEquals(old.generation, current.generation)
        assertFalse(coordinator.markBackendActive(old))
        assertFalse(coordinator.beginStop(old))
        assertFalse(coordinator.confirmBackendStopped(old))
        assertEquals(current.generation, coordinator.snapshot().generation)
        assertEquals(TunLeaseState.RESERVED, coordinator.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, coordinator.snapshot().owner)
    }

    @Test
    fun staleDescriptorTokenCannotReleaseNewGenerationForSameEngine() {
        val coordinator = TunOwnershipCoordinator()
        val old = coordinator.acquire(EngineSlot.TOR)!!
        assertTrue(coordinator.confirmServiceTunClosed(old))

        val current = coordinator.acquire(EngineSlot.TOR)!!
        assertTrue(coordinator.markDescriptorTransferred(current, 9101))
        assertTrue(coordinator.beginStop(current))
        assertFalse(coordinator.acknowledgeEngineCleanup(old, 9101))
        assertFalse(coordinator.confirmServiceTunClosed(old))
        assertEquals(TunLeaseState.STOPPING, coordinator.snapshot().state)
        assertEquals(current.generation, coordinator.snapshot().generation)
        assertEquals(EngineSlot.TOR, coordinator.snapshot().owner)
    }

    @Test
    fun wrongProcessCannotReportDeathOrAcknowledgeCleanup() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquire(EngineSlot.V2RAY_XRAY)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 9201))
        assertFalse(coordinator.reportEngineProcessDeath(lease, 9202))
        assertEquals(TunLeaseState.ACTIVE, coordinator.snapshot().state)
        assertFalse(coordinator.beginStop(lease).not())
        assertTrue(coordinator.beginStop(lease))
        assertFalse(coordinator.acknowledgeEngineCleanup(lease, 9202))
        assertFalse(coordinator.confirmServiceTunClosed(lease))
        assertEquals(EngineSlot.V2RAY_XRAY, coordinator.snapshot().owner)
    }

    @Test
    fun backendManagedLeaseCannotBeReleasedByDescriptorProcessDeathPath() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquireBackendManaged(EngineSlot.WIREGUARD)!!
        assertFalse(coordinator.reportEngineProcessDeath(lease, 9301))
        assertEquals(TunLeaseState.RESERVED, coordinator.snapshot().state)
        assertFalse(coordinator.confirmServiceTunClosed(lease))
        assertEquals(EngineSlot.WIREGUARD, coordinator.snapshot().owner)
        assertTrue(coordinator.beginStop(lease))
        assertTrue(coordinator.confirmBackendStopped(lease))
    }

    @Test
    fun descriptorLeaseRejectsBackendCleanupAcknowledgement() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquire(EngineSlot.AMNEZIA_WG)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 9401))
        assertTrue(coordinator.beginStop(lease))
        assertFalse(coordinator.confirmBackendStopped(lease))
        assertFalse(coordinator.confirmServiceTunClosed(lease))
        assertEquals(TunLeaseState.STOPPING, coordinator.snapshot().state)
    }
}