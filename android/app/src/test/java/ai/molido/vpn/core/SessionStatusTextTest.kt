package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStatusTextTest {
    @Test
    fun idleStatusIncludesOperationDetail() {
        val snapshot = SessionSnapshot(null, SessionState.IDLE, 0)
        assertEquals(
            "Idle — no VPN session\nVPN session stopped.",
            SessionStatusText.render(snapshot, "VPN session stopped.")
        )
    }

    @Test
    fun runningStatusDoesNotClaimPeerHandshake() {
        val snapshot = SessionSnapshot(EngineSlot.WIREGUARD, SessionState.RUNNING, 1)
        assertEquals(
            "Backend active — peer handshake not verified\nConnection still needs device verification.",
            SessionStatusText.render(snapshot, "Connection still needs device verification.")
        )
    }

    @Test
    fun missingDetailReturnsOnlyStateLabel() {
        val snapshot = SessionSnapshot(null, SessionState.IDLE, 0)
        assertEquals("Idle — no VPN session", SessionStatusText.render(snapshot))
    }
}
