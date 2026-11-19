package ai.molido.vpn.core

enum class NetworkPolicy { ANY_VALIDATED, UNMETERED_ONLY, WIFI_ONLY }
enum class PathDecision { ALLOW, BLOCK, UNKNOWN }
data class AdaptivePathDecision(
    val decision: PathDecision,
    val reason: String,
    val transport: NetworkTransport? = null
)

/**
 * Deterministic policy gate. It evaluates measured, fresh network facts only.
 * It does not rank unmeasured engines or claim that a VPN endpoint is reachable.
 */
object AdaptivePathPolicyCore {
    fun evaluate(
        assessment: NetworkAssessment,
        policy: NetworkPolicy
    ): AdaptivePathDecision {
        if (assessment.status == NetworkAssessmentStatus.UNKNOWN ||
            assessment.status == NetworkAssessmentStatus.STALE
        ) return AdaptivePathDecision(PathDecision.UNKNOWN, "Fresh network measurements are unavailable")

        if (assessment.status != NetworkAssessmentStatus.VALIDATED) {
            return AdaptivePathDecision(PathDecision.BLOCK, "Android has not validated internet connectivity", assessment.transport)
        }

        return when (policy) {
            NetworkPolicy.ANY_VALIDATED ->
                AdaptivePathDecision(PathDecision.ALLOW, "Android reports a validated network", assessment.transport)
            NetworkPolicy.UNMETERED_ONLY -> when (assessment.metered) {
                false -> AdaptivePathDecision(PathDecision.ALLOW, "Measured network is unmetered", assessment.transport)
                true -> AdaptivePathDecision(PathDecision.BLOCK, "Policy requires an unmetered network", assessment.transport)
                null -> AdaptivePathDecision(PathDecision.UNKNOWN, "Metered status was not measured", assessment.transport)
            }
            NetworkPolicy.WIFI_ONLY -> when (assessment.transport) {
                NetworkTransport.WIFI -> AdaptivePathDecision(PathDecision.ALLOW, "Measured transport is Wi-Fi", assessment.transport)
                null, NetworkTransport.UNKNOWN -> AdaptivePathDecision(PathDecision.UNKNOWN, "Transport was not measured")
                else -> AdaptivePathDecision(PathDecision.BLOCK, "Policy requires Wi-Fi", assessment.transport)
            }
        }
    }
}
