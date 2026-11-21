package ai.molido.vpn.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TunOwnershipCoordinatorTest {
    @Test
    fun concurrentEngineContentionAllowsExactlyOneLease() {
        val coordinator = TunOwnershipCoordinator()
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(EngineSlot.WIREGUARD, EngineSlot.TOR).map { engine ->
                pool.submit<TunLeaseToken?> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    coordinator.acquire(engine)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val leases = attempts.map { it.get(5, TimeUnit.SECONDS) }.filterNotNull()
            assertEquals(1, leases.size)
            assertEquals(leases.single().engine, coordinator.snapshot().owner)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun wireGuardBackendLeaseIsSharedAndReleasedOnlyAfterConfirmedDown() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquireBackendManaged(EngineSlot.WIREGUARD)!!
        assertEquals(TunLeaseTransport.BACKEND_MANAGED, lease.transport)
        assertTrue(coordinator.markBackendActive(lease))
        assertEquals(TunLeaseState.ACTIVE, coordinator.snapshot().state)
        assertNull(coordinator.acquireBackendManaged(EngineSlot.TOR))
        assertFalse(coordinator.confirmBackendStopped(lease))
        assertTrue(coordinator.beginStop(lease))
        assertTrue(coordinator.confirmBackendStopped(lease))
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
        assertNull(coordinator.snapshot().transport)
        assertNotNull(coordinator.acquireBackendManaged(EngineSlot.TOR))
    }

    @Test
    fun failedWireGuardStartupMustConfirmBackendDownBeforeReleasingLease() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquireBackendManaged(EngineSlot.WIREGUARD)!!
        assertTrue(coordinator.beginStop(lease))
        assertFalse(coordinator.acquireBackendManaged(EngineSlot.TOR).let { it != null })
        assertTrue(coordinator.confirmBackendStopped(lease))
        assertNotNull(coordinator.acquireBackendManaged(EngineSlot.TOR))
    }

    @Test
    fun backendManagedLeaseCannotUseDescriptorHandoffAcknowledgements() {
        val coordinator = TunOwnershipCoordinator()
        val lease = coordinator.acquireBackendManaged(EngineSlot.WIREGUARD)!!
        assertTrue(coordinator.markBackendActive(lease))
        assertTrue(coordinator.beginStop(lease))
        assertFalse(coordinator.acknowledgeEngineCleanup(lease, 1234))
        assertFalse(coordinator.confirmServiceTunClosed(lease))
        assertEquals(TunLeaseState.STOPPING, coordinator.snapshot().state)
        assertTrue(coordinator.confirmBackendStopped(lease))
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
