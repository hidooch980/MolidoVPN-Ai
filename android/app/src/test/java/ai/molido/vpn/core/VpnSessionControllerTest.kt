package ai.molido.vpn.core

import org.junit.Assert.*
import org.junit.Test

class VpnSessionControllerTest {
    private class FakeSession(private var failuresRemaining: Int = 0) : EngineSession {
        var stopCalls = 0
        override fun stop() {
            stopCalls += 1
            if (failuresRemaining > 0) {
                failuresRemaining -= 1
                error("simulated cleanup failure")
            }
        }
    }

    private class FakeAdapter(
        override val slot: EngineSlot,
        private val session: EngineSession = FakeSession()
    ) : EngineAdapter {
        var startCalls = 0
        override fun start(configuration: String): EngineSession {
            startCalls += 1
            return session
        }
    }

    @Test
    fun throwingUiObserverCannotInterruptConnectOrDisconnect() {
        val supervisor = SessionSupervisor()
        val session = FakeSession()
        val adapter = FakeAdapter(EngineSlot.WIREGUARD, session)
        val controller = VpnSessionController(supervisor, listOf(adapter))

        controller.onSessionStateChanged = {
            throw IllegalStateException("simulated UI observer failure")
        }

        assertTrue(controller.connect(EngineSlot.WIREGUARD, "opaque-profile"))
        assertEquals(SessionState.RUNNING, controller.snapshot().state)
        assertTrue(controller.disconnect())
        assertEquals(1, session.stopCalls)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun throwingUiObserverDuringRegistrationDoesNotEscapeSetter() {
        val controller = VpnSessionController(SessionSupervisor(), emptyList())

        controller.onSessionStateChanged = {
            throw IllegalStateException("simulated observer registration failure")
        }

        assertEquals(SessionState.IDLE, controller.snapshot().state)
    }

    @Test
    fun unexpectedWireGuardEngineFailureTransitionsRunningSessionToFailed() {
        val supervisor = SessionSupervisor()
        var failureListener: (() -> Unit)? = null
        val session = FakeSession()
        val adapter = object : EngineAdapter, EngineFailureAwareAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun setFailureListener(listener: (() -> Unit)?) { failureListener = listener }
            override fun start(configuration: String): EngineSession = session
        }
        val controller = VpnSessionController(
            supervisor,
            listOf(adapter),
            networkAssessmentProvider = { NetworkAssessment(NetworkAssessmentStatus.VALIDATED) }
        )
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "in-memory-profile"))
        assertEquals(SessionState.RUNNING, controller.snapshot().state)
        failureListener?.invoke()
        assertEquals(SessionState.FAILED, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertFalse(controller.connect(EngineSlot.TOR, "must-not-start"))
        assertTrue(controller.prepareRecovery(0).action == RecoveryAction.SCHEDULE_RETRY)
    }

    @Test
    fun engineFailureCallbackRacingStartupIsNotLost() {
        val supervisor = SessionSupervisor()
        var failureListener: (() -> Unit)? = null
        val adapter = object : EngineAdapter, EngineFailureAwareAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun setFailureListener(listener: (() -> Unit)?) { failureListener = listener }
            override fun start(configuration: String): EngineSession {
                failureListener?.invoke()
                return FakeSession()
            }
        }
        val controller = VpnSessionController(
            supervisor,
            listOf(adapter),
            networkAssessmentProvider = { NetworkAssessment(NetworkAssessmentStatus.VALIDATED) }
        )
        assertFalse(controller.connect(EngineSlot.WIREGUARD, "in-memory-profile"))
        assertEquals(SessionState.FAILED, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertEquals(RecoveryAction.SCHEDULE_RETRY, controller.prepareRecovery(0).action)
    }

    @Test
    fun duplicateOrLateEngineFailureCallbacksDoNotChangeStoppingOrIdleSession() {
        val supervisor = SessionSupervisor()
        var failureListener: (() -> Unit)? = null
        val adapter = object : EngineAdapter, EngineFailureAwareAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun setFailureListener(listener: (() -> Unit)?) { failureListener = listener }
            override fun start(configuration: String): EngineSession = FakeSession()
        }
        val controller = VpnSessionController(supervisor, listOf(adapter))
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "in-memory-profile"))
        assertTrue(controller.disconnect())
        failureListener?.invoke()
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun networkLossBlocksNewStartUntilValidatedConnectivityReturns() {
        val supervisor = SessionSupervisor()
        val wireguard = FakeAdapter(EngineSlot.WIREGUARD)
        var network = NetworkAssessment(NetworkAssessmentStatus.NOT_VALIDATED)
        val controller = VpnSessionController(
            supervisor, listOf(wireguard), networkAssessmentProvider = { network }
        )
        assertFalse(controller.connect(EngineSlot.WIREGUARD, "offline"))
        assertEquals(0, wireguard.startCalls)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        network = NetworkAssessment(NetworkAssessmentStatus.STALE)
        assertFalse(controller.connect(EngineSlot.WIREGUARD, "stale-observation"))
        assertEquals(0, wireguard.startCalls)
        network = NetworkAssessment(NetworkAssessmentStatus.VALIDATED)
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "after-internet-return"))
        assertEquals(1, wireguard.startCalls)
    }

    @Test
    fun connectsOnlyOneEngineAndDoesNotStartAnotherAdapter() {
        val supervisor = SessionSupervisor()
        val wireguard = FakeAdapter(EngineSlot.WIREGUARD)
        val tor = FakeAdapter(EngineSlot.TOR)
        val controller = VpnSessionController(supervisor, listOf(wireguard, tor))

        assertTrue(controller.connect(EngineSlot.WIREGUARD, "opaque-config"))
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "ignored-second-config"))
        assertFalse(controller.connect(EngineSlot.TOR, "other-config"))
        assertEquals(1, wireguard.startCalls)
        assertEquals(0, tor.startCalls)
        assertEquals(SessionState.RUNNING, controller.snapshot().state)
    }

    @Test
    fun disconnectStopsEngineBeforeReleasingOwnership() {
        val supervisor = SessionSupervisor()
        val session = FakeSession()
        val adapter = FakeAdapter(EngineSlot.WIREGUARD, session)
        val controller = VpnSessionController(supervisor, listOf(adapter))

        assertTrue(controller.connect(EngineSlot.WIREGUARD, "opaque-config"))
        assertTrue(controller.disconnect())
        assertEquals(1, session.stopCalls)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun startupFailureWithCompletedRollbackReleasesReservation() {
        val supervisor = SessionSupervisor()
        val adapter = object : EngineAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String): EngineSession = error("startup failed after rollback")
        }
        val controller = VpnSessionController(supervisor, listOf(adapter))

        try {
            controller.connect(EngineSlot.WIREGUARD, "opaque-config")
            fail("Expected startup failure")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun sharedTunLeaseIsRetainedWhenStartupFailureHasNoCleanupProof() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)
        val adapter = object : TunLeaseAwareEngineAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String, lease: TunLeaseToken): EngineSession {
                error("backend startup outcome is uncertain")
            }
        }
        val competing = object : TunLeaseAwareEngineAdapter {
            override val slot = EngineSlot.TOR
            override fun start(configuration: String, lease: TunLeaseToken): EngineSession =
                error("competing engine must not start")
        }
        val controller = VpnSessionController(supervisor, listOf(adapter, competing))

        try {
            controller.connect(EngineSlot.WIREGUARD, "profile")
            fail("Expected uncertain startup failure")
        } catch (_: IllegalStateException) {
            // The controller must retain the lease without positive cleanup proof.
        }
        assertEquals(SessionState.FAILED, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertEquals(EngineSlot.WIREGUARD, coordinator.snapshot().owner)
        assertEquals(TunLeaseState.RESERVED, coordinator.snapshot().state)
        assertFalse(controller.connect(EngineSlot.TOR, "must-not-overlap"))
    }

    @Test
    fun explicitStartupCleanupProofAllowsReservationRollback() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)
        val adapter = object : TunLeaseAwareEngineAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String, lease: TunLeaseToken): EngineSession =
                throw EngineStartException(
                    "invalid profile before backend startup",
                    cleanupSession = null,
                    cause = IllegalArgumentException("invalid profile"),
                    cleanupConfirmed = true
                )
        }
        val controller = VpnSessionController(supervisor, listOf(adapter))

        try {
            controller.connect(EngineSlot.WIREGUARD, "invalid-profile")
            fail("Expected startup validation failure")
        } catch (_: EngineStartException) {
            // Adapter positively confirmed that no backend resources were started.
        }
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun startupFailureWithPendingCleanupRetainsOwnerForRetry() {
        val supervisor = SessionSupervisor()
        val cleanupSession = FakeSession(failuresRemaining = 1)
        val adapter = object : EngineAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String): EngineSession =
                throw EngineStartException("cleanup pending", cleanupSession, IllegalStateException("startup"))
        }
        val controller = VpnSessionController(supervisor, listOf(adapter))

        try {
            controller.connect(EngineSlot.WIREGUARD, "opaque-config")
            fail("Expected startup failure")
        } catch (_: EngineStartException) {
            // expected
        }
        assertEquals(SessionState.FAILED, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())

        try {
            controller.disconnect()
            fail("Expected first cleanup attempt to fail")
        } catch (_: IllegalStateException) {
            // Cleanup ownership must remain reserved after a failed stop.
        }
        assertEquals(1, cleanupSession.stopCalls)
        assertEquals(SessionState.FAILED, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertEquals(RecoveryAction.NONE, controller.recoveryPlan(0).action)

        assertTrue(controller.disconnect())
        assertEquals(2, cleanupSession.stopCalls)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun stopFailureRetainsOwnerAndAllowsCleanupRetry() {
        val supervisor = SessionSupervisor()
        val failedSession = FakeSession(failuresRemaining = 1)
        val wireguard = FakeAdapter(EngineSlot.WIREGUARD, failedSession)
        val tor = FakeAdapter(EngineSlot.TOR)
        val controller = VpnSessionController(supervisor, listOf(wireguard, tor))

        assertTrue(controller.connect(EngineSlot.WIREGUARD, "opaque-config"))
        try {
            controller.disconnect()
            fail("Expected cleanup failure")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertEquals(SessionState.FAILED, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertFalse(controller.connect(EngineSlot.WIREGUARD, "retry-while-cleanup-pending"))
        assertFalse(controller.connect(EngineSlot.TOR, "other-config"))
        assertEquals(0, tor.startCalls)

        assertTrue(controller.disconnect())
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun recoveryReleasesOldOwnerOnlyAfterSuccessfulCleanup() {
        val supervisor = SessionSupervisor()
        val oldSession = FakeSession()
        val replacementSession = FakeSession()
        val adapter = FakeAdapter(EngineSlot.WIREGUARD, oldSession)
        var network = NetworkAssessment(NetworkAssessmentStatus.VALIDATED)
        val controller = VpnSessionController(
            supervisor, listOf(adapter), networkAssessmentProvider = { network }
        )
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "initial"))
        // A failed session may only be prepared for recovery after it is marked FAILED.
        assertTrue(controller.reportActiveEngineFailure())
        val plan = controller.prepareRecovery(0)
        assertEquals(RecoveryAction.SCHEDULE_RETRY, plan.action)
        assertEquals(1_000L, plan.delayMillis)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "replacement"))
        assertEquals(2, adapter.startCalls)
        assertEquals(SessionState.RUNNING, controller.snapshot().state)
    }

    @Test
    fun failedRecoveryCleanupRetainsOldOwnerAndBlocksEveryEngine() {
        val supervisor = SessionSupervisor()
        val oldSession = FakeSession(failuresRemaining = 1)
        val wireguard = FakeAdapter(EngineSlot.WIREGUARD, oldSession)
        val otherEngine = FakeAdapter(EngineSlot.TOR)
        val controller = VpnSessionController(
            supervisor, listOf(wireguard, otherEngine),
            networkAssessmentProvider = { NetworkAssessment(NetworkAssessmentStatus.VALIDATED) }
        )
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "initial"))
        assertTrue(controller.reportActiveEngineFailure())
        val first = controller.prepareRecovery(0)
        assertEquals(RecoveryAction.NONE, first.action)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertFalse(controller.connect(EngineSlot.TOR, "must-not-overlap"))
        assertEquals(0, otherEngine.startCalls)
        val second = controller.prepareRecovery(0)
        assertEquals(RecoveryAction.SCHEDULE_RETRY, second.action)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun recoveryWaitsForValidatedNetworkAndDoesNotConsumeAttempt() {
        val supervisor = SessionSupervisor()
        val adapter = FakeAdapter(EngineSlot.WIREGUARD)
        var network = NetworkAssessment(NetworkAssessmentStatus.VALIDATED)
        val controller = VpnSessionController(
            supervisor, listOf(adapter), networkAssessmentProvider = { network }
        )
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "initial"))
        assertTrue(controller.reportActiveEngineFailure())
        network = NetworkAssessment(NetworkAssessmentStatus.NOT_VALIDATED)
        val offline = controller.prepareRecovery(2)
        assertEquals(RecoveryAction.WAIT_FOR_NETWORK, offline.action)
        assertEquals(2, offline.attempt)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        network = NetworkAssessment(NetworkAssessmentStatus.VALIDATED)
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "after-network-return"))
        assertEquals(2, adapter.startCalls)
    }

    @Test
    fun missingAdapterCannotReserveSession() {
        val supervisor = SessionSupervisor()
        val controller = VpnSessionController(supervisor, emptyList())
        assertFalse(controller.connect(EngineSlot.WIREGUARD, "opaque-config"))
        assertEquals(SessionState.IDLE, supervisor.snapshot().state)
    }

    @Test
    fun separateControllersCannotStartTheSameOwnedEngineTwice() {
        val supervisor = SessionSupervisor()
        val firstSession = FakeSession()
        val firstAdapter = FakeAdapter(EngineSlot.WIREGUARD, firstSession)
        val secondAdapter = FakeAdapter(EngineSlot.WIREGUARD)
        val firstController = VpnSessionController(supervisor, listOf(firstAdapter))
        val secondController = VpnSessionController(supervisor, listOf(secondAdapter))

        assertTrue(firstController.connect(EngineSlot.WIREGUARD, "first-config"))
        assertFalse(secondController.connect(EngineSlot.WIREGUARD, "second-config"))
        assertEquals(1, firstAdapter.startCalls)
        assertEquals(0, secondAdapter.startCalls)
        assertEquals(SessionState.RUNNING, supervisor.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
    }

    @Test
    fun duplicateAdapterSlotsAreRejectedAtConstruction() {
        val supervisor = SessionSupervisor()
        val first = FakeAdapter(EngineSlot.WIREGUARD)
        val duplicate = FakeAdapter(EngineSlot.WIREGUARD)

        try {
            VpnSessionController(supervisor, listOf(first, duplicate))
            fail("Expected duplicate engine slot rejection")
        } catch (_: IllegalArgumentException) {
            // Duplicate registrations must fail closed before any session can start.
        }

        assertEquals(SessionState.IDLE, supervisor.snapshot().state)
        assertNull(supervisor.currentOwner())
        assertEquals(0, first.startCalls)
        assertEquals(0, duplicate.startCalls)
    }

    @Test
    fun disconnectOnIdleIsIdempotent() {
        val supervisor = SessionSupervisor()
        val controller = VpnSessionController(supervisor, emptyList())

        assertTrue(controller.disconnect())
        assertTrue(controller.disconnect())
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun controllerPassesTheSupervisorLeaseToWireGuardAndReleasesItAfterConfirmedStop() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)
        var receivedLease: TunLeaseToken? = null
        val adapter = object : TunLeaseAwareEngineAdapter {
            override val slot = EngineSlot.WIREGUARD

            override fun start(configuration: String, lease: TunLeaseToken): EngineSession {
                receivedLease = lease
                assertTrue(coordinator.isReserved(lease))
                assertTrue(coordinator.markBackendActive(lease))
                return object : EngineSession {
                    override fun stop() {
                        assertTrue(coordinator.beginStop(lease))
                        assertTrue(coordinator.confirmBackendStopped(lease))
                    }
                }
            }
        }
        val controller = VpnSessionController(supervisor, listOf(adapter))

        assertTrue(controller.connect(EngineSlot.WIREGUARD, "opaque-test-profile"))
        assertNotNull(receivedLease)
        assertEquals(EngineSlot.WIREGUARD, coordinator.snapshot().owner)
        assertEquals(TunLeaseState.ACTIVE, coordinator.snapshot().state)
        assertTrue(controller.disconnect())
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun sharedCoordinatorRejectsSecondSessionWhileFirstLeaseIsHeld() {
        val coordinator = TunOwnershipCoordinator()
        val firstSupervisor = SessionSupervisor(coordinator)
        val secondSupervisor = SessionSupervisor(coordinator)
        val firstAdapter = object : TunLeaseAwareEngineAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String, lease: TunLeaseToken): EngineSession {
                assertTrue(coordinator.markBackendActive(lease))
                return object : EngineSession {
                    override fun stop() {
                        assertTrue(coordinator.beginStop(lease))
                        assertTrue(coordinator.confirmBackendStopped(lease))
                    }
                }
            }
        }
        val firstController = VpnSessionController(firstSupervisor, listOf(firstAdapter))
        var secondStartCalls = 0
        val secondAdapter = object : TunLeaseAwareEngineAdapter {
            override val slot = EngineSlot.TOR
            override fun start(configuration: String, lease: TunLeaseToken): EngineSession {
                secondStartCalls += 1
                error("Competing engine must never start while the first lease is held")
            }
        }
        val secondController = VpnSessionController(secondSupervisor, listOf(secondAdapter))

        assertTrue(firstController.connect(EngineSlot.WIREGUARD, "first"))
        assertFalse(secondController.connect(EngineSlot.TOR, "must-not-overlap"))
        assertEquals(0, secondStartCalls)
        assertEquals(EngineSlot.WIREGUARD, coordinator.snapshot().owner)
        assertTrue(firstController.disconnect())
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
    }

    @Test
    fun disconnectRetainsSessionHandleUntilSharedLeaseReleaseIsConfirmed() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)
        var stopCalls = 0
        val adapter = object : TunLeaseAwareEngineAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String, lease: TunLeaseToken): EngineSession {
                assertTrue(coordinator.markBackendActive(lease))
                return object : EngineSession {
                    override fun stop() {
                        stopCalls += 1
                        assertTrue(coordinator.beginStop(lease))
                        // Simulate a backend whose first stop acknowledgement is incomplete.
                        if (stopCalls == 2) {
                            assertTrue(coordinator.confirmBackendStopped(lease))
                        }
                    }
                }
            }
        }
        val controller = VpnSessionController(supervisor, listOf(adapter))

        assertTrue(controller.connect(EngineSlot.WIREGUARD, "profile"))
        assertFalse(controller.disconnect())
        assertEquals(1, stopCalls)
        assertEquals(SessionState.FAILED, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertEquals(TunLeaseState.STOPPING, coordinator.snapshot().state)

        assertTrue(controller.disconnect())
        assertEquals(2, stopCalls)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun recoveryRetainsCleanupHandleWhenLeaseReleaseIsNotConfirmed() {
        val coordinator = TunOwnershipCoordinator()
        val supervisor = SessionSupervisor(coordinator)
        var stopCalls = 0
        val adapter = object : TunLeaseAwareEngineAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String, lease: TunLeaseToken): EngineSession {
                assertTrue(coordinator.markBackendActive(lease))
                return object : EngineSession {
                    override fun stop() {
                        stopCalls += 1
                        assertTrue(coordinator.beginStop(lease))
                        if (stopCalls == 2) {
                            assertTrue(coordinator.confirmBackendStopped(lease))
                        }
                    }
                }
            }
        }
        val controller = VpnSessionController(
            supervisor,
            listOf(adapter),
            networkAssessmentProvider = { NetworkAssessment(NetworkAssessmentStatus.VALIDATED) }
        )

        assertTrue(controller.connect(EngineSlot.WIREGUARD, "profile"))
        assertTrue(controller.reportActiveEngineFailure())
        assertEquals(RecoveryAction.NONE, controller.prepareRecovery(0).action)
        assertEquals(SessionState.FAILED, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertEquals(RecoveryAction.SCHEDULE_RETRY, controller.prepareRecovery(0).action)
        assertEquals(2, stopCalls)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertEquals(TunLeaseState.IDLE, coordinator.snapshot().state)
    }


    @Test
    fun failureCallbackDuringStopDoesNotBreakRetryAfterStopThrows() {
        val supervisor = SessionSupervisor()
        var failureListener: (() -> Unit)? = null
        var stopCalls = 0
        val session = object : EngineSession {
            override fun stop() {
                stopCalls += 1
                // Simulate a DOWN/process-failure callback delivered synchronously
                // while disconnect() is already executing its stop operation.
                failureListener?.invoke()
                if (stopCalls == 1) error("first cleanup attempt failed")
            }
        }
        val adapter = object : EngineAdapter, EngineFailureAwareAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun setFailureListener(listener: (() -> Unit)?) {
                failureListener = listener
            }
            override fun start(configuration: String): EngineSession = session
        }
        val controller = VpnSessionController(supervisor, listOf(adapter))

        assertTrue(controller.connect(EngineSlot.WIREGUARD, "profile"))
        try {
            controller.disconnect()
            fail("Expected first cleanup attempt to fail")
        } catch (_: IllegalStateException) {
            // The failed stop must retain the owner and permit a second attempt.
        }
        assertEquals(1, stopCalls)
        assertEquals(SessionState.FAILED, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())

        assertTrue(controller.disconnect())
        assertEquals(2, stopCalls)
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }


    @Test
    fun runtimeHealthIsSampledOnlyForTheRunningActiveSessionAndRecorded() {
        val diagnostics = DiagnosticsObservabilityCore(enabled = true)
        val expected = TunnelHealthAssessment(
            state = TunnelHealthState.HANDSHAKE_RECENT,
            peerActivityObserved = true,
            explanation = "Peer handshake observed"
        )
        var sampledAt = -1L
        val adapter = object : EngineAdapter, TunnelHealthAwareAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String): EngineSession = FakeSession()
            override fun runtimeHealth(nowEpochMillis: Long): TunnelHealthAssessment {
                sampledAt = nowEpochMillis
                return expected
            }
        }
        val controller = VpnSessionController(
            SessionSupervisor(), listOf(adapter), diagnostics = diagnostics,
            nowEpochMillis = { 123_456L }
        )
        assertNull(controller.observeActiveTunnelHealth())
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "profile"))
        assertEquals(expected, controller.observeActiveTunnelHealth())
        assertEquals(123_456L, sampledAt)
        assertEquals("TUNNEL_HEALTH_HANDSHAKE_RECENT", diagnostics.snapshot().last().code)
    }

    @Test
    fun telemetryReadFailureBecomesInvalidAndDoesNotChangeSessionOwnership() {
        val diagnostics = DiagnosticsObservabilityCore(enabled = true)
        val adapter = object : EngineAdapter, TunnelHealthAwareAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String): EngineSession = FakeSession()
            override fun runtimeHealth(nowEpochMillis: Long): TunnelHealthAssessment? {
                throw IllegalStateException("backend diagnostics unavailable")
            }
        }
        val supervisor = SessionSupervisor()
        val controller = VpnSessionController(supervisor, listOf(adapter), diagnostics = diagnostics)
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "profile"))
        val health = controller.observeActiveTunnelHealth()
        assertEquals(TunnelHealthState.INVALID_TELEMETRY, health?.state)
        assertEquals(SessionState.RUNNING, controller.snapshot().state)
        assertEquals(EngineSlot.WIREGUARD, supervisor.currentOwner())
        assertEquals("TUNNEL_HEALTH_INVALID_TELEMETRY", diagnostics.snapshot().single().code)
        assertFalse(diagnostics.snapshot().last().detail.contains("backend diagnostics unavailable"))
    }

    @Test
    fun healthSamplingIsUnavailableAfterSessionFailureOrDisconnect() {
        val adapter = object : EngineAdapter, TunnelHealthAwareAdapter {
            override val slot = EngineSlot.WIREGUARD
            override fun start(configuration: String): EngineSession = FakeSession()
            override fun runtimeHealth(nowEpochMillis: Long): TunnelHealthAssessment =
                TunnelHealthAssessment(TunnelHealthState.HANDSHAKE_PENDING, explanation = "waiting")
        }
        val controller = VpnSessionController(SessionSupervisor(), listOf(adapter))
        assertTrue(controller.connect(EngineSlot.WIREGUARD, "profile"))
        assertTrue(controller.reportActiveEngineFailure())
        assertNull(controller.observeActiveTunnelHealth())
        assertTrue(controller.disconnect())
        assertNull(controller.observeActiveTunnelHealth())
    }

}
