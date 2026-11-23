package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionImportTest {
    private val priv = "A".repeat(43) + "="
    private val pub = "B".repeat(43) + "="
    private val link = "wireguard://${priv.replace("=", "%3D")}@1.2.3.4:51820?publickey=${pub.replace("=", "%3D")}&address=10.0.0.2%2F32&mtu=1280#DE%20Berlin"

    @Test
    fun convertsWireGuardLinkToConf() {
        val conf = SubscriptionImport.fromUri(link)!!
        assertTrue(conf.startsWith("# DE Berlin"))
        assertTrue(conf.contains("PrivateKey = $priv"))
        assertTrue(conf.contains("PublicKey = $pub"))
        assertTrue(conf.contains("Address = 10.0.0.2/32"))
        assertTrue(conf.contains("Endpoint = 1.2.3.4:51820"))
    }

    @Test
    fun rejectsIncompleteLink() {
        assertNull(SubscriptionImport.fromUri("wireguard://short@1.2.3.4:51820"))
    }

    @Test
    fun base64SubscriptionKeepsWireGuardAndCountsOthers() {
        val body = listOf(link, "vless://x@h:443#a", "vmess://abc").joinToString("\n")
        val result = SubscriptionImport.parse(Base64.getEncoder().encodeToString(body.toByteArray()))
        assertEquals(1, result.wireGuardCount)
        assertEquals(2, result.unsupportedCount)
        assertEquals(1, WireGuardBundle.split(result.wireGuardText).size)
    }

    @Test
    fun plainConfBodyIsImported() {
        val conf = "# NL\n[Interface]\nPrivateKey = $priv\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = $pub\nAllowedIPs = 0.0.0.0/0\nEndpoint = 5.6.7.8:51820"
        val result = SubscriptionImport.parse(conf)
        assertEquals(1, result.wireGuardCount)
        assertEquals(0, result.unsupportedCount)
    }
}
