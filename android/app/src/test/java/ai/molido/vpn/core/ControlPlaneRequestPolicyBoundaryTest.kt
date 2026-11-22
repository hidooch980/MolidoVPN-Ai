package ai.molido.vpn.core

import org.junit.Assert.assertThrows
import org.junit.Test

class ControlPlaneRequestPolicyBoundaryTest {
    @Test
    fun acceptsMaximumTokenLengthAndTimeoutBoundaries() {
        ControlPlaneRequestPolicy.validateDeviceToken("a".repeat(ControlPlaneRequestPolicy.MAX_DEVICE_TOKEN_LENGTH))
        ControlPlaneRequestPolicy.validateTimeouts(
            ControlPlaneRequestPolicy.MIN_TIMEOUT_MILLIS,
            ControlPlaneRequestPolicy.MIN_TIMEOUT_MILLIS
        )
        ControlPlaneRequestPolicy.validateTimeouts(
            ControlPlaneRequestPolicy.MAX_CONNECT_TIMEOUT_MILLIS,
            ControlPlaneRequestPolicy.MAX_READ_TIMEOUT_MILLIS
        )
    }

    @Test
    fun rejectsEveryAsciiHeaderControlCharacter() {
        val controls = (0..31).map(Int::toChar) + 127.toChar()
        controls.forEach { control ->
            assertThrows(IllegalArgumentException::class.java) {
                ControlPlaneRequestPolicy.validateDeviceToken("token${control}tail")
            }
        }
    }

    @Test
    fun rejectsNonAsciiAndWhitespaceTokenCharacters() {
        listOf("token with-space", "token${0x00A0.toChar()}nbsp", "token${0x2003.toChar()}em-space", "tökén").forEach { token ->
            assertThrows(IllegalArgumentException::class.java) {
                ControlPlaneRequestPolicy.validateDeviceToken(token)
            }
        }
    }
}
