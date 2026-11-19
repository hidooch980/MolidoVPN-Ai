package ai.molido.vpn.core

import java.security.PublicKey
import java.security.Signature
import java.security.MessageDigest

data class SignedUpdateManifest(
    val versionCode: Long,
    val expiresAtEpochSeconds: Long,
    val artifactSha256Hex: String,
    val signedPayload: ByteArray,
    val signature: ByteArray
)

enum class UpdateVerificationStatus { VERIFIED, EXPIRED, ROLLBACK, INVALID_DIGEST, INVALID_SIGNATURE, INVALID_METADATA }

data class UpdateVerificationResult(val status: UpdateVerificationStatus, val reason: String)

/** Verifies signed metadata and artifact digest; it does not download or install APKs. */
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

        if (manifest.expiresAtEpochSeconds < nowEpochSeconds) {
            return UpdateVerificationResult(UpdateVerificationStatus.EXPIRED, "Signed update metadata has expired")
        }
        if (manifest.versionCode <= installedVersionCode) {
            return UpdateVerificationResult(UpdateVerificationStatus.ROLLBACK, "Update version is not newer than installed version")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact)
            .joinToString("") { "%02x".format(it) }
        if (!digest.equals(manifest.artifactSha256Hex, ignoreCase = true)) {
            return UpdateVerificationResult(UpdateVerificationStatus.INVALID_DIGEST, "Artifact digest does not match signed metadata")
        }
        val valid = try {
            Signature.getInstance(signatureAlgorithm).run {
                initVerify(trustedPublicKey)
                update(manifest.signedPayload)
                verify(manifest.signature)
            }
        } catch (_: Exception) {
            false
        }
        return if (valid) UpdateVerificationResult(UpdateVerificationStatus.VERIFIED, "Signature and artifact digest verified")
        else UpdateVerificationResult(UpdateVerificationStatus.INVALID_SIGNATURE, "Update metadata signature is invalid")
    }
}
