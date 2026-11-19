package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ResilienceRecoveryCoreTest {
    private val online = NetworkAssessment(NetworkAssessmentStatus.VALIDATED)
    @Test fun offlineWaitsWithoutConsumingRetry() {
        val result = ResilienceRecoveryCore.plan(SessionState.FAILED, NetworkAssessment(NetworkAssessmentStatus.NOT_VALIDATED), 2)
        assertEquals(RecoveryAction.WAIT_FOR_NETWORK, result.action)
        assertEquals(2, result.attempt)
    }
    @Test fun backoffIsBoundedAndExponential() {
        assertEquals(1_000L, ResilienceRecoveryCore.plan(SessionState.FAILED, online, 0).delayMillis)
        assertEquals(4_000L, ResilienceRecoveryCore.plan(SessionState.FAILED, online, 2).delayMillis)
        val capped = ResilienceRecoveryCore.plan(SessionState.FAILED, online, 20, maxAttempts = 25)
        assertEquals(RecoveryAction.SCHEDULE_RETRY, capped.action)
        assertEquals(60_000L, capped.delayMillis)
        assertEquals(21, capped.attempt)
    }

    @Test fun activeOrTransitionalSessionsDoNotScheduleRetries() {
        for (state in listOf(SessionState.IDLE, SessionState.RESERVED, SessionState.RUNNING, SessionState.STOPPING)) {
            val result = ResilienceRecoveryCore.plan(state, online, 0)
            assertEquals(state.name, RecoveryAction.NONE, result.action)
            assertEquals(state.name, 0, result.attempt)
        }
    }
    @Test fun retryLimitStops() {
        assertEquals(RecoveryAction.STOP_RETRIES, ResilienceRecoveryCore.plan(SessionState.FAILED, online, 5).action)
    }
}
