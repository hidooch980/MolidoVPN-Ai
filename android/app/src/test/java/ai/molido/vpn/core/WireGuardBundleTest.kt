package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardBundleTest {
    private fun conf(key: String) = "[Interface]\nPrivateKey = $key\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = P$key\nEndpoint = 1.2.3.4:51820"

    @Test
    fun singleConfigWithoutCommentIsOneEntry() {
        val entries = WireGuardBundle.split(conf("A"))
        assertEquals(1, entries.size)
        assertEquals("WireGuard 1", entries[0].name)
        assertTrue(entries[0].text.contains("[Peer]"))
    }

    @Test
    fun joinedFilesSplitBackWithNamesAndCountries() {
        val bundle = WireGuardBundle.join(listOf("germany-1.conf" to conf("A"), "x.conf" to "# 🇳🇱 NL fast\n" + conf("B"), "de2.conf" to conf("C")))
        val entries = WireGuardBundle.split(bundle)
        assertEquals(3, entries.size)
        assertEquals(listOf("germany-1", "🇳🇱 NL fast", "de2"), entries.map { it.name })
        assertEquals("DE", CountryIndex.countryOf(entries[0].name))
        assertEquals("NL", CountryIndex.countryOf(entries[1].name))
        assertTrue(entries.none { it.text.contains("PrivateKey = B") && it.text.contains("PrivateKey = A") })
    }

    @Test
    fun selectPicksCountryOrFallsBackToFirst() {
        val bundle = WireGuardBundle.join(listOf("germany.conf" to conf("A"), "netherlands.conf" to conf("B")))
        assertTrue(WireGuardBundle.select(bundle, "NL").contains("PrivateKey = B"))
        assertTrue(WireGuardBundle.select(bundle, null).contains("PrivateKey = A"))
        assertTrue(WireGuardBundle.select(bundle, "JP").contains("PrivateKey = A"))
    }

    @Test
    fun candidatesListEveryConfigOfTheCountryInOrder() {
        val bundle = WireGuardBundle.join(listOf("germany-1.conf" to conf("A"), "netherlands.conf" to conf("B"), "germany-2.conf" to conf("C")))
        val de = WireGuardBundle.candidates(bundle, "DE")
        assertEquals(2, de.size)
        assertTrue(de[0].contains("PrivateKey = A"))
        assertTrue(de[1].contains("PrivateKey = C"))
        assertEquals(3, WireGuardBundle.candidates(bundle, "JP").size)
        assertEquals(listOf("vless://x"), WireGuardBundle.candidates("vless://x", "DE"))
    }

    @Test
    fun textWithoutInterfaceIsReturnedUnchanged() {
        assertEquals("vless://x", WireGuardBundle.select("vless://x", "DE"))
    }
}
