package ai.molido.vpn.core

import org.junit.Assert.assertThrows
import org.junit.Test

class ControlPlaneRequestPolicyTest {
    @Test
    fun acceptsBoundedTimeoutsAndOpaqueToken() {
        ControlPlaneRequestPolicy.validateTimeouts(10_000, 15_000)
        ControlPlaneRequestPolicy.validateDeviceToken("device-token_123.abc")
    }

    @Test
    fun rejectsZeroNegativeAndExcessiveTimeouts() {
        assertThrows(IllegalArgumentException::class.java) {
            ControlPlaneRequestPolicy.validateTimeouts(0, 15_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlPlaneRequestPolicy.validateTimeouts(10_000, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlPlaneRequestPolicy.validateTimeouts(60_001, 15_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ControlPlaneRequestPolicy.validateTimeouts(10_000, 120_001)
        }
    }

    @Test
    fun rejectsBlankOversizedAndHeaderInjectionTokens() {
        listOf("", " ", "x".repeat(513), "token\r\nInjected: yes", "token\ttab").forEach { token ->
            assertThrows(IllegalArgumentException::class.java) {
                ControlPlaneRequestPolicy.validateDeviceToken(token)
            }
        }
    }
}
