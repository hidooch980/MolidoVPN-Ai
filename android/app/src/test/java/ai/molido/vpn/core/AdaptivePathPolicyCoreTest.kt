package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptivePathPolicyCoreTest {
    @Test fun staleMeasurementsNeverAuthorizePath() {
        val result = AdaptivePathPolicyCore.evaluate(
            NetworkAssessment(NetworkAssessmentStatus.STALE), NetworkPolicy.ANY_VALIDATED
        )
        assertEquals(PathDecision.UNKNOWN, result.decision)
    }

    @Test fun unmeteredPolicyRequiresMeasuredFalseMeteredFlag() {
        val missing = AdaptivePathPolicyCore.evaluate(
            NetworkAssessment(NetworkAssessmentStatus.VALIDATED, NetworkTransport.WIFI),
            NetworkPolicy.UNMETERED_ONLY
        )
        assertEquals(PathDecision.UNKNOWN, missing.decision)
        val allowed = AdaptivePathPolicyCore.evaluate(
            NetworkAssessment(NetworkAssessmentStatus.VALIDATED, NetworkTransport.WIFI, metered = false),
            NetworkPolicy.UNMETERED_ONLY
        )
        assertEquals(PathDecision.ALLOW, allowed.decision)
    }

    @Test fun wifiPolicyBlocksMeasuredCellular() {
        val result = AdaptivePathPolicyCore.evaluate(
            NetworkAssessment(NetworkAssessmentStatus.VALIDATED, NetworkTransport.CELLULAR),
            NetworkPolicy.WIFI_ONLY
        )
        assertEquals(PathDecision.BLOCK, result.decision)
    }
}
