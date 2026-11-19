package ai.molido.vpn.core

import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

data class SignedControlConfiguration(
    val revision: Long,
    val expiresAtEpochSeconds: Long,
    val payload: ByteArray,
    val signature: ByteArray
)

enum class ConfigurationStatus { ACCEPTED, EXPIRED, ROLLBACK, INVALID_METADATA, INVALID_SIGNATURE }
data class ConfigurationVerification(val status: ConfigurationStatus, val reason: String)

/**
 * Verifies signed control-plane configuration received from an operator-managed
 * backend. It deliberately has no network client, persistence, or embedded trust key.
 */
class ControlPlaneConfigurationCore(
    private val trustedPublicKey: PublicKey,
    private val signatureAlgorithm: String = "SHA256withECDSA"
) {
    fun verify(
        config: SignedControlConfiguration,
        currentRevision: Long,
        nowEpochSeconds: Long
    ): ConfigurationVerification {
        if (config.revision <= 0 || currentRevision < 0 || config.expiresAtEpochSeconds <= 0 ||
            nowEpochSeconds < 0 || config.payload.isEmpty() || config.payload.size > MAX_CONFIG_BYTES ||
            config.signature.isEmpty()
        ) return ConfigurationVerification(ConfigurationStatus.INVALID_METADATA, "Malformed configuration envelope")
        if (config.expiresAtEpochSeconds <= nowEpochSeconds)
            return ConfigurationVerification(ConfigurationStatus.EXPIRED, "Configuration has expired")
        if (config.revision <= currentRevision)
            return ConfigurationVerification(ConfigurationStatus.ROLLBACK, "Configuration revision is not newer")
        val canonical = canonicalPayload(config.revision, config.expiresAtEpochSeconds, config.payload)
        val valid = try {
            Signature.getInstance(signatureAlgorithm).run {
                initVerify(trustedPublicKey); update(canonical); verify(config.signature)
            }
        } catch (_: Exception) { false }
        return if (valid) ConfigurationVerification(ConfigurationStatus.ACCEPTED, "Configuration signature verified")
        else ConfigurationVerification(ConfigurationStatus.INVALID_SIGNATURE, "Configuration signature is invalid")
    }

    companion object {
        const val MAX_CONFIG_BYTES = 64 * 1024
        fun canonicalPayload(revision: Long, expiresAtEpochSeconds: Long, payload: ByteArray): ByteArray {
            val header = "$revision\n$expiresAtEpochSeconds\n".toByteArray(Charsets.UTF_8)
            return header + payload
        }
    }
}
