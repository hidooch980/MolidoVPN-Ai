package ai.molido.vpn.engine.singbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class ShareLinkParserTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(StandardCharsets.UTF_8))

    @Test fun parsesVlessReality() {
        val node = ShareLinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality" +
                "&sni=www.microsoft.com&pbk=PUBKEY&sid=ab12&fp=chrome&flow=xtls-rprx-vision#DE%20Frankfurt"
        )!!
        assertEquals(ProxyProtocol.VLESS, node.protocol)
        assertEquals("example.com", node.host)
        assertEquals(443, node.port)
        assertEquals("DE Frankfurt", node.name)
        assertEquals("reality", node.params["security"])
        val json = SingBoxConfigBuilder.build(node)
        assertTrue(json.contains("\"public_key\":\"PUBKEY\""))
        assertTrue(json.contains("\"flow\":\"xtls-rprx-vision\""))
        assertTrue(json.contains("\"strict_route\":true"))
    }

    @Test fun parsesTrojanAndKeepsPlusInPassword() {
        val node = ShareLinkParser.parse("trojan://pa+ss@1.2.3.4:8443?sni=a.example#t")!!
        assertEquals(ProxyProtocol.TROJAN, node.protocol)
        assertEquals("pa+ss", node.credential)
        assertEquals(8443, node.port)
    }

    @Test fun parsesShadowsocksSip002AndLegacy() {
        val sip = ShareLinkParser.parse("ss://${b64("2022-blake3-aes-128-gcm:secret")}@[2001:db8::1]:8388#ss")!!
        assertEquals("2001:db8::1", sip.host)
        assertEquals("2022-blake3-aes-128-gcm", sip.secondary)
        assertEquals("secret", sip.credential)

        val legacy = ShareLinkParser.parse("ss://${b64("aes-256-gcm:pw@10.0.0.1:443")}#old")!!
        assertEquals("10.0.0.1", legacy.host)
        assertEquals("aes-256-gcm", legacy.secondary)
        assertEquals("old", legacy.name)
    }

    @Test fun parsesVmessJson() {
        val json = """{"v":"2","ps":"NL","add":"v.example","port":"443","id":"uuid-1","aid":"0","net":"ws","path":"/ws","host":"cdn.example","tls":"tls"}"""
        val node = ShareLinkParser.parse("vmess://" + b64(json))!!
        assertEquals(ProxyProtocol.VMESS, node.protocol)
        assertEquals("NL", node.name)
        val out = SingBoxConfigBuilder.build(node)
        assertTrue(out.contains("\"type\":\"ws\""))
        assertTrue(out.contains("\"Host\":\"cdn.example\""))
    }

    @Test fun parsesHysteria2AndTuic() {
        val hy2 = ShareLinkParser.parse("hy2://pw@h.example:443?obfs=salamander&obfs-password=x&sni=h.example")!!
        assertEquals(ProxyProtocol.HYSTERIA2, hy2.protocol)
        assertTrue(SingBoxConfigBuilder.build(hy2).contains("\"salamander\""))

        val tuic = ShareLinkParser.parse("tuic://uuid-2:pw2@t.example:443?congestion_control=bbr&alpn=h3")!!
        assertEquals("uuid-2", tuic.credential)
        assertEquals("pw2", tuic.secondary)
    }

    @Test fun parsesBase64Subscription() {
        val body = b64("trojan://a@x.example:443#1\nnot-a-link\nhy2://b@y.example:443#2\n")
        val nodes = ShareLinkParser.parseSubscription(body)
        assertEquals(2, nodes.size)
    }

    @Test fun rejectsInvalidLinks() {
        assertNull(ShareLinkParser.parse(""))
        assertNull(ShareLinkParser.parse("http://example.com"))
        assertNull(ShareLinkParser.parse("vless://@host:443"))
        assertNull(ShareLinkParser.parse("trojan://pw@host:99999"))
        assertNull(ShareLinkParser.parse("tuic://onlyuuid@host:443"))
    }

    @Test fun nodeToStringDoesNotLeakSecrets() {
        val node = ShareLinkParser.parse("trojan://topsecret@x.example:443")
        assertNotNull(node)
        assertFalse(node.toString().contains("topsecret"))
    }

    @Test fun perAppOptionsAndJsonEscaping() {
        val node = ShareLinkParser.parse("trojan://p%22q@x.example:443")!!
        val json = SingBoxConfigBuilder.build(node, SingBoxConfigBuilder.Options(includePackages = listOf("org.telegram.messenger")))
        assertTrue(json.contains("\"include_package\":[\"org.telegram.messenger\"]"))
        assertFalse(json.contains("exclude_package"))
        assertTrue(json.contains("p\\\"q"))
    }
}
