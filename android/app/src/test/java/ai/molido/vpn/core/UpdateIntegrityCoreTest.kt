package ai.molido.vpn.core

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateIntegrityCoreTest {
    @Test fun verifiesSignedMetadataAndArtifactDigest() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = "version=2;expires=2000".toByteArray()
        val artifact = "apk-bytes".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(payload); sign()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact)
            .joinToString("") { "%02x".format(it) }
        val manifest = SignedUpdateManifest(2, 2_000, digest, payload, signature)
        assertEquals(UpdateVerificationStatus.VERIFIED,
            UpdateIntegrityCore(pair.public).verify(manifest, artifact, 1, 1).status)
    }

    @Test fun rejectsRollbackAndModifiedArtifact() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = byteArrayOf(1)
        val artifact = byteArrayOf(2)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(payload); sign()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact)
            .joinToString("") { "%02x".format(it) }
        val manifest = SignedUpdateManifest(2, 100, digest, payload, signature)
        val verifier = UpdateIntegrityCore(pair.public)
        assertEquals(UpdateVerificationStatus.ROLLBACK, verifier.verify(manifest, artifact, 2, 1).status)
        assertEquals(UpdateVerificationStatus.INVALID_DIGEST, verifier.verify(manifest, byteArrayOf(3), 1, 1).status)
    }
}
