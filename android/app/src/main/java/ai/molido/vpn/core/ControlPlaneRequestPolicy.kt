package ai.molido.vpn.core

/**
 * Validates control-plane request parameters before they reach HTTP APIs.
 * Bounds prevent accidental infinite waits and header control-character injection.
 */
object ControlPlaneRequestPolicy {
    const val MAX_DEVICE_TOKEN_LENGTH = 512
    const val MIN_TIMEOUT_MILLIS = 1
    const val MAX_CONNECT_TIMEOUT_MILLIS = 60_000
    const val MAX_READ_TIMEOUT_MILLIS = 120_000
    private const val VISIBLE_ASCII_TOKEN_RANGE = 0x21..0x7E

    fun validateTimeouts(connectTimeoutMillis: Int, readTimeoutMillis: Int) {
        require(connectTimeoutMillis in MIN_TIMEOUT_MILLIS..MAX_CONNECT_TIMEOUT_MILLIS) {
            "Control-plane connect timeout is outside the allowed range"
        }
        require(readTimeoutMillis in MIN_TIMEOUT_MILLIS..MAX_READ_TIMEOUT_MILLIS) {
            "Control-plane read timeout is outside the allowed range"
        }
    }

    fun validateDeviceToken(deviceToken: String) {
        require(deviceToken.isNotBlank() && deviceToken.length <= MAX_DEVICE_TOKEN_LENGTH) {
            "Control-plane device token is missing or too long"
        }
        require(deviceToken.all { it.code in VISIBLE_ASCII_TOKEN_RANGE }) {
            "Control-plane device token contains invalid header characters"
        }
    }
}
