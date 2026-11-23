package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class UpdateManifestParserTest {
    private val sha = "a".repeat(64)

    private fun manifest(version: Long, expires: Long, apk: String, sig: ByteArray) =
        """{"versionCode":$version,"expiresAt":$expires,"sha256":"$sha","apk":"$apk","signature":"${Base64.getEncoder().encodeToString(sig)}"}"""

    @Test fun pipelineSignedManifestVerifiesEndToEnd() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val artifact = ByteArray(32) { it.toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(artifact).joinToString("") { "%02x".format(it) }
        // Mirrors the CI step: sign "versionCode\nexpiresAt\nsha256\n" with SHA256withECDSA.
        val payload = UpdateIntegrityCore.canonicalPayload(7, 4_000_000_000, digest)
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(payload); sign() }
        val json = """{"versionCode":7,"expiresAt":4000000000,"sha256":"$digest","apk":"MolidoVPN-v1.1.7.apk","signature":"${Base64.getEncoder().encodeToString(sig)}"}"""
        val parsed = UpdateManifestParser.parse(json)!!
        assertEquals("MolidoVPN-v1.1.7.apk", parsed.apkAssetName)
        val result = UpdateIntegrityCore(keys.public).verify(parsed.manifest, artifact, installedVersionCode = 3, nowEpochSeconds = 1_000)
        assertEquals(UpdateVerificationStatus.VERIFIED, result.status)
    }

    @Test fun rejectsPathTraversalAndMalformedFields() {
        val sig = byteArrayOf(1, 2, 3)
        assertNull(UpdateManifestParser.parse(manifest(5, 9, "../evil.apk", sig)))
        assertNull(UpdateManifestParser.parse(manifest(5, 9, "MolidoVPN-v1.apk/../x", sig)))
        assertNull(UpdateManifestParser.parse(manifest(0, 9, "MolidoVPN-v1.1.5.apk", sig)))
        assertNull(UpdateManifestParser.parse("""{"versionCode":5}"""))
        assertNull(UpdateManifestParser.parse("x".repeat(UpdateManifestParser.MAX_MANIFEST_BYTES + 1)))
    }
}
