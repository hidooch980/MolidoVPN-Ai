package ai.molido.vpn.core

import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature

data class SignedUpdateManifest(
    val versionCode: Long,
    val expiresAtEpochSeconds: Long,
    val artifactSha256Hex: String,
    val signedPayload: ByteArray,
    val signature: ByteArray
)

enum class UpdateVerificationStatus { VERIFIED, EXPIRED, ROLLBACK, INVALID_DIGEST, INVALID_SIGNATURE, INVALID_METADATA }
data class UpdateVerificationResult(val status: UpdateVerificationStatus, val reason: String)

/**
 * Verifies a canonical signed manifest and its APK digest. The trusted public key
 * must be provisioned by the release owner; this class never generates or embeds keys.
 * Download, installation, persistent anti-rollback storage and recovery are separate gates.
 */
class UpdateIntegrityCore(
    private val trustedPublicKey: PublicKey,
    private val signatureAlgorithm: String = "SHA256withECDSA"
) {
    fun verify(
        manifest: SignedUpdateManifest,
        artifact: ByteArray,
        installedVersionCode: Long,
        nowEpochSeconds: Long
    ): UpdateVerificationResult {
        if (manifest.versionCode <= 0 || installedVersionCode < 0 ||
            manifest.expiresAtEpochSeconds <= 0 || nowEpochSeconds < 0 ||
            !manifest.artifactSha256Hex.matches(Regex("[a-fA-F0-9]{64}")) ||
            manifest.signedPayload.isEmpty() || manifest.signature.isEmpty()
        ) return UpdateVerificationResult(UpdateVerificationStatus.INVALID_METADATA, "Malformed update metadata")

        if (manifest.expiresAtEpochSeconds <= nowEpochSeconds) {
            return UpdateVerificationResult(UpdateVerificationStatus.EXPIRED, "Signed update metadata has expired")
        }
        if (manifest.versionCode <= installedVersionCode) {
            return UpdateVerificationResult(UpdateVerificationStatus.ROLLBACK, "Update version is not newer than installed version")
        }

        val canonical = canonicalPayload(
            manifest.versionCode, manifest.expiresAtEpochSeconds, manifest.artifactSha256Hex.lowercase()
        )
        if (!MessageDigest.isEqual(canonical, manifest.signedPayload)) {
            return UpdateVerificationResult(UpdateVerificationStatus.INVALID_METADATA, "Signed payload does not match manifest fields")
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(artifact)
            .joinToString("") { "%02x".format(it) }
        if (!digest.equals(manifest.artifactSha256Hex, ignoreCase = true)) {
            return UpdateVerificationResult(UpdateVerificationStatus.INVALID_DIGEST, "Artifact digest does not match signed metadata")
        }

        val valid = try {
            Signature.getInstance(signatureAlgorithm).run {
                initVerify(trustedPublicKey)
                update(canonical)
                verify(manifest.signature)
            }
        } catch (_: Exception) { false }

        return if (valid) UpdateVerificationResult(UpdateVerificationStatus.VERIFIED, "Signature and artifact digest verified")
        else UpdateVerificationResult(UpdateVerificationStatus.INVALID_SIGNATURE, "Update metadata signature is invalid")
    }

    companion object {
        /** Stable UTF-8 wire format: versionCode, expiry epoch, lowercase SHA-256, each newline-delimited. */
        fun canonicalPayload(versionCode: Long, expiresAtEpochSeconds: Long, artifactSha256Hex: String): ByteArray =
            "$versionCode\n$expiresAtEpochSeconds\n${artifactSha256Hex.lowercase()}\n".toByteArray(Charsets.UTF_8)
    }
}
