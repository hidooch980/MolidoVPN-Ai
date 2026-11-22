package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNetworkObservationMonitorTest {
    @Test
    fun observerReceivesNullWhenNetworkIsUnavailable() {
        var received: NetworkObservation? = NetworkObservation(
            transport = NetworkTransport.WIFI,
            validation = NetworkValidation.VALIDATED,
            observedAtEpochMillis = 1L,
            metered = false,
            downstreamKbps = 100,
            upstreamKbps = 50
        )

        val delivered = deliverNetworkObservationSafely({ received = it }, null)

        assertTrue(delivered)
        assertEquals(null, received)
    }

    @Test
    fun observerReceivesObservationWithoutMutation() {
        val expected = NetworkObservation(
            transport = NetworkTransport.CELLULAR,
            validation = NetworkValidation.NOT_VALIDATED,
            observedAtEpochMillis = 42L,
            metered = true,
            downstreamKbps = null,
            upstreamKbps = 12
        )
        var received: NetworkObservation? = null

        val delivered = deliverNetworkObservationSafely({ received = it }, expected)

        assertTrue(delivered)
        assertEquals(expected, received)
    }

    @Test
    fun throwingObserverIsContainedAndReported() {
        val delivered = deliverNetworkObservationSafely(
            observer = { throw IllegalStateException("simulated observer failure") },
            observation = null
        )

        assertFalse(delivered)
    }
}
