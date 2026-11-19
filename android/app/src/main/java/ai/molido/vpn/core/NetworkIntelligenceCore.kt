package ai.molido.vpn.core

/**
 * A point-in-time network observation supplied by an explicitly identified local
 * platform collector. Null fields mean "not measured", never zero or a guessed value.
 */
data class NetworkObservation(
    val transport: NetworkTransport,
    val validation: NetworkValidation,
    val observedAtEpochMillis: Long,
    val metered: Boolean? = null,
    val downstreamKbps: Int? = null,
    val upstreamKbps: Int? = null
) {
    init {
        require(observedAtEpochMillis >= 0) { "Observation timestamp must be non-negative" }
        require(downstreamKbps == null || downstreamKbps >= 0) { "Downstream rate cannot be negative" }
        require(upstreamKbps == null || upstreamKbps >= 0) { "Upstream rate cannot be negative" }
    }
}

enum class NetworkTransport { WIFI, CELLULAR, ETHERNET, VPN, OTHER, UNKNOWN }
enum class NetworkValidation { VALIDATED, NOT_VALIDATED, UNKNOWN }
enum class NetworkAssessmentStatus { UNKNOWN, VALIDATED, NOT_VALIDATED, STALE }

data class NetworkAssessment(
    val status: NetworkAssessmentStatus,
    val transport: NetworkTransport? = null,
    val metered: Boolean? = null,
    val downstreamKbps: Int? = null,
    val upstreamKbps: Int? = null,
    val ageMillis: Long? = null
)

/**
 * Deterministic, offline evaluator for locally measured network facts.
 *
 * This class does not probe the internet, estimate throughput, select a VPN engine,
 * or transmit telemetry. Callers must provide a timestamped observation.
 */
object NetworkIntelligenceCore {
    const val DEFAULT_MAX_AGE_MILLIS = 30_000L

    fun assess(
        observation: NetworkObservation?,
        nowEpochMillis: Long,
        maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS
    ): NetworkAssessment {
        require(nowEpochMillis >= 0) { "Current timestamp must be non-negative" }
        require(maxAgeMillis > 0) { "Maximum observation age must be positive" }

        if (observation == null || observation.observedAtEpochMillis > nowEpochMillis) {
            return NetworkAssessment(NetworkAssessmentStatus.UNKNOWN)
        }

        val age = nowEpochMillis - observation.observedAtEpochMillis
        if (age > maxAgeMillis) {
            return NetworkAssessment(NetworkAssessmentStatus.STALE, ageMillis = age)
        }

        val status = when (observation.validation) {
            NetworkValidation.VALIDATED -> NetworkAssessmentStatus.VALIDATED
            NetworkValidation.NOT_VALIDATED -> NetworkAssessmentStatus.NOT_VALIDATED
            NetworkValidation.UNKNOWN -> NetworkAssessmentStatus.UNKNOWN
        }

        return NetworkAssessment(
            status = status,
            transport = observation.transport,
            metered = observation.metered,
            downstreamKbps = observation.downstreamKbps,
            upstreamKbps = observation.upstreamKbps,
            ageMillis = age
        )
    }
}
