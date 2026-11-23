package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WarpRegistrationTest {
    private val config = listOf(
        "# Cloudflare WARP",
        "[Interface]",
        "PrivateKey = K",
        "Address = 172.16.0.2/32",
        "",
        "[Peer]",
        "PublicKey = P",
        "AllowedIPs = 0.0.0.0/0, ::/0",
        "Endpoint = engage.cloudflareclient.com:2408"
    ).joinToString("\n")

    @Test
    fun variantsKeepOriginalFirstAndUseDistinctWarpEndpoints() {
        val entries = WireGuardBundle.split(WarpRegistration.withEndpoints(config, 6, Random(42)))
        assertEquals(6, entries.size)
        val endpoints = entries.map { e -> e.text.lines().first { it.startsWith("Endpoint") }.substringAfter("= ") }
        assertEquals("engage.cloudflareclient.com:2408", endpoints[0])
        assertEquals(6, endpoints.toSet().size)
        assertTrue(endpoints[1].startsWith("8."))
        endpoints.drop(1).forEach { ep ->
            val (ip, port) = ep.split(':')
            assertTrue(WarpRegistration.ENDPOINT_RANGES.contains(ip.substringBeforeLast('.')))
            assertTrue(WarpRegistration.ENDPOINT_PORTS.contains(port.toInt()))
        }
        assertTrue(entries.all { it.text.contains("PrivateKey = K") })
    }
}
