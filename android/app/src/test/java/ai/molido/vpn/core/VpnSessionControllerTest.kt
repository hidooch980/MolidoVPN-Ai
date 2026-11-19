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
        assertTrue(controller.disconnect())
        assertEquals(SessionState.IDLE, controller.snapshot().state)
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
        assertFalse(controller.connect(EngineSlot.TOR, "other-config"))
        assertEquals(0, tor.startCalls)

        assertTrue(controller.disconnect())
        assertEquals(SessionState.IDLE, controller.snapshot().state)
        assertNull(supervisor.currentOwner())
    }

    @Test
    fun missingAdapterCannotReserveSession() {
        val supervisor = SessionSupervisor()
        val controller = VpnSessionController(supervisor, emptyList())
        assertFalse(controller.connect(EngineSlot.WIREGUARD, "opaque-config"))
        assertEquals(SessionState.IDLE, supervisor.snapshot().state)
    }
}
