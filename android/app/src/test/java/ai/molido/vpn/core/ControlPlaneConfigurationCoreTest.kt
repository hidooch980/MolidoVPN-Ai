package ai.molido.vpn.core

import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlPlaneConfigurationCoreTest {
    @Test fun acceptsOnlyFreshSignedIncreasingConfiguration() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = """{"policy":"wifi-only"}""".toByteArray()
        val canonical = ControlPlaneConfigurationCore.canonicalPayload(2, 500, payload)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(canonical); sign()
        }
        val config = SignedControlConfiguration(2, 500, payload, signature)
        val verifier = ControlPlaneConfigurationCore(pair.public)
        assertEquals(ConfigurationStatus.ACCEPTED, verifier.verify(config, 1, 1).status)
        assertEquals(ConfigurationStatus.ROLLBACK, verifier.verify(config, 2, 1).status)
        assertEquals(ConfigurationStatus.EXPIRED, verifier.verify(config, 1, 500).status)
    }
}
