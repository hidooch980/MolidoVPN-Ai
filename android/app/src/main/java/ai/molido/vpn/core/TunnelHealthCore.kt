package ai.molido.vpn.core

/**
 * Evidence-based WireGuard tunnel health assessment.
 *
 * A recent WireGuard handshake proves peer-level protocol activity only. It does
 * NOT prove DNS, general internet reachability, or application data-plane health.
 * This evaluator never invents latency, packet loss, or throughput measurements.
 */
enum class TunnelHealthState {
    STOPPED,
    STARTING,
    HANDSHAKE_PENDING,
    HANDSHAKE_RECENT,
    HANDSHAKE_STALE,
    INVALID_TELEMETRY
}

data class TunnelHealthAssessment(
    val state: TunnelHealthState,
    val sessionAgeMillis: Long? = null,
    val handshakeAgeMillis: Long? = null,
    val receivedBytes: Long? = null,
    val transmittedBytes: Long? = null,
    val peerActivityObserved: Boolean = false,
    val internetReachabilityVerified: Boolean = false,
    val explanation: String
)

object TunnelHealthCore {
    const val DEFAULT_STARTUP_GRACE_MILLIS = 30_000L
    const val DEFAULT_MAX_HANDSHAKE_AGE_MILLIS = 180_000L

    fun assess(
        backendUp: Boolean,
        sessionStartedAtEpochMillis: Long?,
        nowEpochMillis: Long,
        latestHandshakeEpochMillis: Long?,
        receivedBytes: Long?,
        transmittedBytes: Long?,
        startupGraceMillis: Long = DEFAULT_STARTUP_GRACE_MILLIS,
        maxHandshakeAgeMillis: Long = DEFAULT_MAX_HANDSHAKE_AGE_MILLIS
    ): TunnelHealthAssessment {
        require(nowEpochMillis >= 0) { "Current timestamp must be non-negative" }
        require(startupGraceMillis >= 0) { "Startup grace cannot be negative" }
        require(maxHandshakeAgeMillis > 0) { "Handshake freshness window must be positive" }

        if (!backendUp) {
            return TunnelHealthAssessment(
                state = TunnelHealthState.STOPPED,
                explanation = "WireGuard backend is not UP"
            )
        }

        val startedAt = sessionStartedAtEpochMillis
            ?: return invalid("Active backend has no session start timestamp")
        if (startedAt < 0 || startedAt > nowEpochMillis) {
            return invalid("Session start timestamp is invalid or in the future")
        }
        if (receivedBytes != null && receivedBytes < 0 ||
            transmittedBytes != null && transmittedBytes < 0
        ) return invalid("Traffic counters cannot be negative")

        val sessionAge = nowEpochMillis - startedAt
        val handshake = latestHandshakeEpochMillis
        if (handshake != null && handshake < 0) {
            return invalid("Handshake timestamp cannot be negative", sessionAge)
        }
        if (handshake != null && handshake > nowEpochMillis) {
            return invalid("Handshake timestamp is in the future", sessionAge)
        }
        if (handshake != null && handshake != 0L && handshake < startedAt) {
            return invalid("Handshake predates the active session", sessionAge)
        }

        val validHandshake = handshake?.takeIf { it > 0L }
        if (validHandshake == null) {
            val state = if (sessionAge <= startupGraceMillis) {
                TunnelHealthState.HANDSHAKE_PENDING
            } else {
                TunnelHealthState.HANDSHAKE_STALE
            }
            return TunnelHealthAssessment(
                state = state,
                sessionAgeMillis = sessionAge,
                receivedBytes = receivedBytes,
                transmittedBytes = transmittedBytes,
                explanation = if (state == TunnelHealthState.HANDSHAKE_PENDING)
                    "Waiting for the first peer handshake"
                else "No peer handshake has been observed within the startup grace period"
            )
        }

        val handshakeAge = nowEpochMillis - validHandshake
        return TunnelHealthAssessment(
            state = if (handshakeAge <= maxHandshakeAgeMillis)
                TunnelHealthState.HANDSHAKE_RECENT else TunnelHealthState.HANDSHAKE_STALE,
            sessionAgeMillis = sessionAge,
            handshakeAgeMillis = handshakeAge,
            receivedBytes = receivedBytes,
            transmittedBytes = transmittedBytes,
            peerActivityObserved = true,
            explanation = if (handshakeAge <= maxHandshakeAgeMillis)
                "A recent WireGuard peer handshake was observed; internet reachability is not established"
            else "The latest WireGuard peer handshake is older than the configured freshness window"
        )
    }

    private fun invalid(reason: String, sessionAge: Long? = null) =
        TunnelHealthAssessment(
            state = TunnelHealthState.INVALID_TELEMETRY,
            sessionAgeMillis = sessionAge,
            explanation = reason
        )
}
