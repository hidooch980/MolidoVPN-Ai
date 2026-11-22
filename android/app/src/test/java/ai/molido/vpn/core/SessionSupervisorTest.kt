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
    fun sameEngineCannotClaimAnExistingReservationAgain() {
        val supervisor = SessionSupervisor()
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertFalse(supervisor.claim(EngineSlot.WIREGUARD))
        assertEquals(SessionState.RESERVED, supervisor.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertEquals(1L, supervisor.snapshot().generation)
    }

    @Test
    fun sameEngineCannotClaimAnAlreadyRunningSessionAgain() {
        val supervisor = SessionSupervisor()
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertTrue(supervisor.markRunning(EngineSlot.WIREGUARD))
        assertFalse(supervisor.claim(EngineSlot.WIREGUARD))
        assertEquals(SessionState.RUNNING, supervisor.snapshot().state)
        assertEquals(1L, supervisor.snapshot().generation)
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

    @Test
    fun sharedCoordinatorLeaseMustBeActivatedAndCleanedBeforeSupervisorRelease() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)

        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        val lease = supervisor.tunLeaseFor(EngineSlot.WIREGUARD)!!
        assertTrue(coordinator.isReserved(lease))
        assertFalse(supervisor.markRunning(EngineSlot.WIREGUARD))

        assertTrue(coordinator.markBackendActive(lease))
        assertTrue(supervisor.markRunning(EngineSlot.WIREGUARD))
        assertTrue(supervisor.beginStopping(EngineSlot.WIREGUARD))
        assertEquals(TunLeaseState.STOPPING, coordinator.snapshot().state)
        assertTrue(coordinator.confirmBackendStopped(lease))
        assertTrue(supervisor.release(EngineSlot.WIREGUARD))
        assertEquals(SessionState.IDLE, supervisor.snapshot().state)
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
    }

    @Test
    fun supervisorCannotReleaseSharedLeaseBeforeTransportCleanup() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)

        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertFalse(supervisor.release(EngineSlot.WIREGUARD))
        assertEquals(EngineSlot.WIREGUARD, coordinator.snapshot().owner)
        assertFalse(supervisor.claim(EngineSlot.TOR))
    }

    @Test
    fun backendManagedStartupReservationCannotBeReleasedWithoutCleanupProof() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)

        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertTrue(supervisor.markFailed(EngineSlot.WIREGUARD))
        assertFalse(supervisor.releaseUnstartedReservation(EngineSlot.WIREGUARD))
        assertEquals(EngineSlot.WIREGUARD, coordinator.snapshot().owner)
        assertEquals(TunLeaseState.RESERVED, coordinator.snapshot().state)
        assertFalse(supervisor.claim(EngineSlot.TOR))

        assertTrue(supervisor.releaseUnstartedReservation(
            EngineSlot.WIREGUARD,
            backendCleanupConfirmed = true
        ))
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
    }

    @Test
    fun startupReservationCanBeRolledBackOnlyBeforeTransportActivation() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)

        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
        assertTrue(supervisor.markFailed(EngineSlot.WIREGUARD))
        assertTrue(supervisor.releaseUnstartedReservation(
            EngineSlot.WIREGUARD,
            backendCleanupConfirmed = true
        ))
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
        assertEquals(SessionState.IDLE, supervisor.snapshot().state)
    }


    @Test
    fun isolatedEngineDeathCanEnterStoppingAndRetainsSessionUntilServiceClosesTun() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)

        assertTrue(supervisor.claim(EngineSlot.V2RAY_XRAY))
        val lease = supervisor.tunLeaseFor(EngineSlot.V2RAY_XRAY)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 10421))
        assertTrue(supervisor.markRunning(EngineSlot.V2RAY_XRAY))

        // Binder/process death is not cleanup confirmation; coordinator enters
        // CLEANUP_REQUIRED and keeps the exclusive lease.
        assertTrue(coordinator.reportEngineProcessDeath(lease, 10421))
        assertEquals(TunLeaseState.CLEANUP_REQUIRED, coordinator.snapshot().state)
        assertFalse(supervisor.release(EngineSlot.V2RAY_XRAY))
        assertFalse(supervisor.claim(EngineSlot.WIREGUARD))

        // The facade must still be able to enter STOPPING after the death event.
        assertTrue(supervisor.beginStopping(EngineSlot.V2RAY_XRAY))
        assertEquals(SessionState.STOPPING, supervisor.snapshot().state)
        assertFalse(supervisor.release(EngineSlot.V2RAY_XRAY))

        // Only the central service's confirmed close permits both ledgers to release.
        assertTrue(coordinator.confirmServiceTunClosed(lease))
        assertTrue(supervisor.release(EngineSlot.V2RAY_XRAY))
        assertEquals(SessionState.IDLE, supervisor.snapshot().state)
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
    }


    @Test
    fun isolatedEngineDeathDuringStartupCanRollbackOnlyAfterServiceClosesTun() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)

        assertTrue(supervisor.claim(EngineSlot.AMNEZIA_WG))
        val lease = supervisor.tunLeaseFor(EngineSlot.AMNEZIA_WG)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 10422))

        // Startup has not been published as RUNNING yet, but the process owns an FD copy.
        assertTrue(coordinator.reportEngineProcessDeath(lease, 10422))
        assertTrue(supervisor.markFailed(EngineSlot.AMNEZIA_WG))
        assertFalse(supervisor.releaseUnstartedReservation(EngineSlot.AMNEZIA_WG))
        assertFalse(supervisor.claim(EngineSlot.WIREGUARD))

        assertTrue(coordinator.confirmServiceTunClosed(lease))
        assertTrue(supervisor.releaseUnstartedReservation(EngineSlot.AMNEZIA_WG))
        assertEquals(SessionState.IDLE, supervisor.snapshot().state)
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
        assertTrue(supervisor.claim(EngineSlot.WIREGUARD))
    }


    @Test
    fun lateFailureCallbackDoesNotRegressStoppingAfterProcessDeath() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)

        assertTrue(supervisor.claim(EngineSlot.V2RAY_XRAY))
        val lease = supervisor.tunLeaseFor(EngineSlot.V2RAY_XRAY)!!
        assertTrue(coordinator.markDescriptorTransferred(lease, 10423))
        assertTrue(supervisor.markRunning(EngineSlot.V2RAY_XRAY))
        assertTrue(supervisor.beginStopping(EngineSlot.V2RAY_XRAY))
        assertEquals(SessionState.STOPPING, supervisor.snapshot().state)

        // A death/failure callback can race with an already-started stop operation.
        assertTrue(coordinator.reportEngineProcessDeath(lease, 10423))
        assertTrue(supervisor.markFailed(EngineSlot.V2RAY_XRAY))
        assertEquals(SessionState.STOPPING, supervisor.snapshot().state)

        assertTrue(coordinator.confirmServiceTunClosed(lease))
        assertTrue(supervisor.release(EngineSlot.V2RAY_XRAY))
        assertEquals(SessionState.IDLE, supervisor.snapshot().state)
    }

}
