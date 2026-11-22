package ai.molido.vpn.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInstallRecoveryPolicyTest {
    @Test
    fun livePendingSessionContinuesBlockingAnotherUpdate() {
        assertTrue(PendingInstallRecoveryPolicy.isSessionActive(42, listOf(7, 42, 99)))
    }

    @Test
    fun staleOrMissingPendingSessionCanBeReconciled() {
        assertFalse(PendingInstallRecoveryPolicy.isSessionActive(42, listOf(7, 99)))
        assertFalse(PendingInstallRecoveryPolicy.isSessionActive(-1, listOf(7, 99)))
    }

    @Test
    fun unrelatedActiveSessionDoesNotMatchPendingSession() {
        assertFalse(PendingInstallRecoveryPolicy.isSessionActive(42, listOf(43)))
    }
}
