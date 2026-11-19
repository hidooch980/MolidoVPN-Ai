package ai.molido.vpn.core

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateIntegrityCoreTest {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun verifiesCanonicalSignedMetadataAndArtifactDigest() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val artifact = "apk-bytes".toByteArray()
        val hash = digest(artifact)
        val payload = UpdateIntegrityCore.canonicalPayload(2, 2_000, hash)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(payload); sign()
        }
        val manifest = SignedUpdateManifest(2, 2_000, hash, payload, signature)
        assertEquals(UpdateVerificationStatus.VERIFIED,
            UpdateIntegrityCore(pair.public).verify(manifest, artifact, 1, 1).status)
    }

    @Test fun rejectsRollbackModifiedArtifactAndFieldPayloadMismatch() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val artifact = byteArrayOf(2)
        val hash = digest(artifact)
        val payload = UpdateIntegrityCore.canonicalPayload(2, 100, hash)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(payload); sign()
        }
        val manifest = SignedUpdateManifest(2, 100, hash, payload, signature)
        val verifier = UpdateIntegrityCore(pair.public)
        assertEquals(UpdateVerificationStatus.ROLLBACK, verifier.verify(manifest, artifact, 2, 1).status)
        assertEquals(UpdateVerificationStatus.INVALID_DIGEST, verifier.verify(manifest, byteArrayOf(3), 1, 1).status)
        val tamperedFields = manifest.copy(versionCode = 3)
        assertEquals(UpdateVerificationStatus.INVALID_METADATA, verifier.verify(tamperedFields, artifact, 1, 1).status)
    }

    @Test fun rejectsInvalidSignatureAndMalformedMetadata() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val otherPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val artifact = byteArrayOf(9, 8, 7)
        val hash = digest(artifact)
        val payload = UpdateIntegrityCore.canonicalPayload(3, 500, hash)
        val wrongSignature = Signature.getInstance("SHA256withECDSA").run {
            initSign(otherPair.private); update(payload); sign()
        }
        val verifier = UpdateIntegrityCore(pair.public)
        val invalidSignatureManifest = SignedUpdateManifest(3, 500, hash, payload, wrongSignature)
        assertEquals(UpdateVerificationStatus.INVALID_SIGNATURE,
            verifier.verify(invalidSignatureManifest, artifact, 1, 1).status)

        val malformed = invalidSignatureManifest.copy(artifactSha256Hex = "not-a-sha256")
        assertEquals(UpdateVerificationStatus.INVALID_METADATA,
            verifier.verify(malformed, artifact, 1, 1).status)
    }

    @Test fun rejectsExpiredManifest() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val artifact = byteArrayOf(1)
        val hash = digest(artifact)
        val payload = UpdateIntegrityCore.canonicalPayload(2, 100, hash)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(payload); sign()
        }
        val manifest = SignedUpdateManifest(2, 100, hash, payload, signature)
        assertEquals(UpdateVerificationStatus.EXPIRED,
            UpdateIntegrityCore(pair.public).verify(manifest, artifact, 1, 100).status)
    }
}
