package ai.molido.vpn.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyRedactorTest {
    @Test fun redactsCredentialsAndIpv4Addresses() {
        val result = PrivacyRedactor.redact("token=abc password:xyz host=203.0.113.5")
        assertFalse(result.contains("abc"))
        assertFalse(result.contains("xyz"))
        assertFalse(result.contains("203.0.113.5"))
        assertTrue(result.contains("<redacted>"))
        assertTrue(result.contains("<ip-redacted>"))
    }
}
