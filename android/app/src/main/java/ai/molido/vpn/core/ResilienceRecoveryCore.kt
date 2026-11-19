package ai.molido.vpn.core

enum class RecoveryAction { NONE, WAIT_FOR_NETWORK, SCHEDULE_RETRY, STOP_RETRIES }
data class RecoveryPlan(val action: RecoveryAction, val delayMillis: Long = 0, val attempt: Int, val reason: String)

/** Pure bounded reconnect policy; scheduling and VPN restart remain supervisor responsibilities. */
object ResilienceRecoveryCore {
    const val DEFAULT_MAX_ATTEMPTS = 5
    const val BASE_DELAY_MILLIS = 1_000L
    const val MAX_DELAY_MILLIS = 60_000L

    fun plan(sessionState: SessionState, network: NetworkAssessment, attempt: Int,
             maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): RecoveryPlan {
        require(attempt >= 0)
        require(maxAttempts >= 0)
        if (sessionState != SessionState.FAILED)
            return RecoveryPlan(RecoveryAction.NONE, attempt = attempt, reason = "Session is not in a recoverable failed state")
        if (network.status != NetworkAssessmentStatus.VALIDATED)
            return RecoveryPlan(RecoveryAction.WAIT_FOR_NETWORK, attempt = attempt, reason = "Wait for fresh validated connectivity")
        if (attempt >= maxAttempts)
            return RecoveryPlan(RecoveryAction.STOP_RETRIES, attempt = attempt, reason = "Retry limit reached")
        val delay = (BASE_DELAY_MILLIS * (1L shl attempt.coerceAtMost(16))).coerceAtMost(MAX_DELAY_MILLIS)
        return RecoveryPlan(RecoveryAction.SCHEDULE_RETRY, delay, attempt + 1, "Bounded exponential backoff")
    }
}
