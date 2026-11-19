package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkIntelligenceCoreTest {
    @Test
    fun missingObservationRemainsUnknown() {
        assertEquals(
            NetworkAssessmentStatus.UNKNOWN,
            NetworkIntelligenceCore.assess(null, nowEpochMillis = 10_000).status
        )
    }

    @Test
    fun validatedObservationPreservesOnlyMeasuredValues() {
        val result = NetworkIntelligenceCore.assess(
            NetworkObservation(
                transport = NetworkTransport.WIFI,
                validation = NetworkValidation.VALIDATED,
                observedAtEpochMillis = 9_000,
                metered = false,
                downstreamKbps = 12_000
            ),
            nowEpochMillis = 10_000
        )

        assertEquals(NetworkAssessmentStatus.VALIDATED, result.status)
        assertEquals(NetworkTransport.WIFI, result.transport)
        assertEquals(false, result.metered)
        assertEquals(12_000, result.downstreamKbps)
        assertNull(result.upstreamKbps)
        assertEquals(1_000L, result.ageMillis)
    }

    @Test
    fun unvalidatedDoesNotGetMisreportedAsOffline() {
        val result = NetworkIntelligenceCore.assess(
            NetworkObservation(
                NetworkTransport.CELLULAR,
                NetworkValidation.NOT_VALIDATED,
                observedAtEpochMillis = 10_000
            ),
            nowEpochMillis = 10_000
        )
        assertEquals(NetworkAssessmentStatus.NOT_VALIDATED, result.status)
    }

    @Test
    fun staleObservationDoesNotLeakOldMeasurementsIntoAssessment() {
        val result = NetworkIntelligenceCore.assess(
            NetworkObservation(
                NetworkTransport.WIFI,
                NetworkValidation.VALIDATED,
                observedAtEpochMillis = 1_000,
                downstreamKbps = 50_000
            ),
            nowEpochMillis = 32_001
        )

        assertEquals(NetworkAssessmentStatus.STALE, result.status)
        assertNull(result.transport)
        assertNull(result.downstreamKbps)
        assertEquals(31_001L, result.ageMillis)
    }

    @Test
    fun futureObservationIsRejectedAsUnknown() {
        val result = NetworkIntelligenceCore.assess(
            NetworkObservation(
                NetworkTransport.ETHERNET,
                NetworkValidation.VALIDATED,
                observedAtEpochMillis = 11_000
            ),
            nowEpochMillis = 10_000
        )
        assertEquals(NetworkAssessmentStatus.UNKNOWN, result.status)
        assertNull(result.transport)
    }
}
