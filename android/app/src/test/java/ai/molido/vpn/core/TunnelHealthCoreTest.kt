package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelHealthCoreTest {
    @Test fun backendDownIsStoppedAndDoesNotClaimReachability() {
        val result = TunnelHealthCore.assess(
            backendUp = false,
            sessionStartedAtEpochMillis = null,
            nowEpochMillis = 10_000,
            latestHandshakeEpochMillis = null,
            receivedBytes = null,
            transmittedBytes = null
        )
        assertEquals(TunnelHealthState.STOPPED, result.state)
        assertFalse(result.internetReachabilityVerified)
        assertFalse(result.peerActivityObserved)
    }

    @Test fun startupGraceAllowsAHandshakeToArrive() {
        val result = TunnelHealthCore.assess(
            true, 10_000, 39_999, 0, 0, 0
        )
        assertEquals(TunnelHealthState.HANDSHAKE_PENDING, result.state)
        assertEquals(29_999L, result.sessionAgeMillis)
    }

    @Test fun missingHandshakeAfterGraceIsStaleNotHealthy() {
        val result = TunnelHealthCore.assess(
            true, 10_000, 40_001, null, 0, 0
        )
        assertEquals(TunnelHealthState.HANDSHAKE_STALE, result.state)
        assertFalse(result.peerActivityObserved)
    }

    @Test fun recentHandshakeIsPeerActivityButNotInternetProof() {
        val result = TunnelHealthCore.assess(
            true, 10_000, 100_000, 99_000, 512, 256
        )
        assertEquals(TunnelHealthState.HANDSHAKE_RECENT, result.state)
        assertEquals(1_000L, result.handshakeAgeMillis)
        assertTrue(result.peerActivityObserved)
        assertFalse(result.internetReachabilityVerified)
        assertEquals(512L, result.receivedBytes)
        assertEquals(256L, result.transmittedBytes)
    }

    @Test fun handshakeFreshnessBoundaryIsInclusive() {
        val fresh = TunnelHealthCore.assess(
            true, 1, 180_001, 1, 0, 0,
            startupGraceMillis = 0,
            maxHandshakeAgeMillis = 180_000
        )
        assertEquals(TunnelHealthState.HANDSHAKE_RECENT, fresh.state)

        val stale = TunnelHealthCore.assess(
            true, 1, 180_002, 1, 0, 0,
            startupGraceMillis = 0,
            maxHandshakeAgeMillis = 180_000
        )
        assertEquals(TunnelHealthState.HANDSHAKE_STALE, stale.state)
    }

    @Test fun futureSessionTimestampFailsClosed() {
        val result = TunnelHealthCore.assess(true, 11, 10, null, 0, 0)
        assertEquals(TunnelHealthState.INVALID_TELEMETRY, result.state)
    }

    @Test fun futureHandshakeTimestampFailsClosed() {
        val result = TunnelHealthCore.assess(true, 1, 10, 11, 0, 0)
        assertEquals(TunnelHealthState.INVALID_TELEMETRY, result.state)
    }

    @Test fun handshakeFromPreviousSessionFailsClosed() {
        val result = TunnelHealthCore.assess(true, 20, 30, 19, 0, 0)
        assertEquals(TunnelHealthState.INVALID_TELEMETRY, result.state)
    }

    @Test fun negativeCountersFailClosed() {
        val result = TunnelHealthCore.assess(true, 1, 10, null, -1, 0)
        assertEquals(TunnelHealthState.INVALID_TELEMETRY, result.state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroFreshnessWindowIsRejected() {
        TunnelHealthCore.assess(true, 1, 10, null, 0, 0, maxHandshakeAgeMillis = 0)
    }
}
