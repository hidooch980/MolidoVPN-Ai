package ai.molido.vpn.core

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsPrivacyTest {
    @Test
    fun diagnosticRingBufferRedactsWireGuardSecretsAndBearerTokens() {
        val diagnostics = DiagnosticsObservabilityCore(capacity = 8, enabled = true)
        val privateKey = "WG-PRIVATE-KEY-DO-NOT-LEAK"
        val token = "DEVICE-BEARER-TOKEN-DO-NOT-LEAK"
        val detail = "PrivateKey = $privateKey; PresharedKey=$privateKey; Authorization: Bearer $token; endpoint 203.0.113.17"
        diagnostics.record("TEST_EVENT", detail, 1_700_000_000_000)

        val stored = diagnostics.snapshot().single().detail
        assertFalse(stored.contains(privateKey))
        assertFalse(stored.contains(token))
        assertFalse(stored.contains("203.0.113.17"))
        assertTrue(stored.contains("<redacted>"))
        assertTrue(stored.contains("<ip-redacted>"))
    }

    @Test
    fun disabledDiagnosticsNeverRetainsSubmittedContent() {
        val diagnostics = DiagnosticsObservabilityCore(enabled = false)
        diagnostics.record("TEST_EVENT", "PrivateKey = sensitive-value", 1)
        assertTrue(diagnostics.snapshot().isEmpty())
    }

    @Test
    fun diagnosticsRejectUnstableOrPotentiallySensitiveEventCodes() {
        val diagnostics = DiagnosticsObservabilityCore(enabled = true)
        try {
            diagnostics.record("private-key-value", "generic", 1)
            fail("Event codes must be stable identifiers")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertTrue(diagnostics.snapshot().isEmpty())
    }
}
