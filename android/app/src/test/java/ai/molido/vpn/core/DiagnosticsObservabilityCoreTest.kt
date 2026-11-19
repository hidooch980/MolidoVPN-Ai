package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsObservabilityCoreTest {
    @Test fun disabledByDefaultRecordsNothing() {
        val core = DiagnosticsObservabilityCore()
        core.record("SESSION_START", "safe", 1)
        assertTrue(core.snapshot().isEmpty())
    }

    @Test fun boundedBufferDropsOldestAndRedactsSecrets() {
        val core = DiagnosticsObservabilityCore(capacity = 2, enabled = true)
        core.record("ONE", "private_key=abc123 endpoint=192.0.2.1", 1)
        core.record("TWO", "Bearer super-secret", 2)
        core.record("THREE", "ok", 3)
        val events = core.snapshot()
        assertEquals(listOf("TWO", "THREE"), events.map { it.code })
        assertTrue(core.snapshot().none { it.detail.contains("super-secret") })
    }
}
