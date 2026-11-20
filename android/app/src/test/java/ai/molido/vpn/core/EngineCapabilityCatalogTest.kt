package ai.molido.vpn.core

import org.junit.Assert.*
import org.junit.Test

class EngineCapabilityCatalogTest {
    private class FakeAdapter(override val slot: EngineSlot) : EngineAdapter {
        override fun start(configuration: String): EngineSession = error("not used by catalog test")
    }

    @Test
    fun onlyRegisteredAdaptersAreSelectable() {
        val catalog = EngineCapabilityCatalog.build(listOf(FakeAdapter(EngineSlot.WIREGUARD)))
        assertEquals(6, catalog.size)
        assertTrue(catalog.single { it.slot == EngineSlot.WIREGUARD }.selectable)
        assertEquals(EngineReadiness.SOURCE_IMPLEMENTED,
            catalog.single { it.slot == EngineSlot.WIREGUARD }.readiness)
        assertFalse(catalog.filter { it.slot != EngineSlot.WIREGUARD }.any { it.selectable })
    }

    @Test
    fun registeredAdapterIsNotAutomaticallyDeviceOrProductionVerified() {
        val entry = EngineCapabilityCatalog.build(listOf(FakeAdapter(EngineSlot.WIREGUARD)))
            .single { it.slot == EngineSlot.WIREGUARD }
        assertEquals(EngineReadiness.SOURCE_IMPLEMENTED, entry.readiness)
        assertFalse(entry.countrySelectionSupported)
    }

    @Test
    fun countriesAppearOnlyForExplicitlyVerifiedProviderInventory() {
        val catalog = EngineCapabilityCatalog.build(
            adapters = listOf(FakeAdapter(EngineSlot.WIREGUARD)),
            verifiedSlots = setOf(EngineSlot.WIREGUARD),
            countryInventories = mapOf(EngineSlot.WIREGUARD to listOf("DE", "NL"))
        )
        val entry = catalog.single { it.slot == EngineSlot.WIREGUARD }
        assertTrue(entry.countrySelectionSupported)
        assertEquals(listOf("DE", "NL"), entry.availableCountries)
        assertEquals(EngineReadiness.ANDROID_DEVICE_TESTED, entry.readiness)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unregisteredEngineCannotBeMarkedDeviceVerified() {
        EngineCapabilityCatalog.build(
            adapters = emptyList(),
            verifiedSlots = setOf(EngineSlot.TOR)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedCountrySelectorCannotExposeCountries() {
        EngineCapability(
            slot = EngineSlot.MASQUE_WARP,
            displayName = "WARP",
            readiness = EngineReadiness.NOT_IMPLEMENTED,
            adapterRegistered = false,
            countrySelectionSupported = false,
            availableCountries = listOf("US"),
            statusDetail = "invalid"
        )
    }

    @Test
    fun externalLegalAndProtocolBlockersAreExplicitAndNotSelectable() {
        val catalog = EngineCapabilityCatalog.build(adapters = emptyList())
        val psiphon = catalog.single { it.slot == EngineSlot.PSIPHON }
        val warp = catalog.single { it.slot == EngineSlot.MASQUE_WARP }

        assertEquals(EngineReadiness.BLOCKED, psiphon.readiness)
        assertFalse(psiphon.selectable)
        assertTrue(psiphon.statusDetail.contains("GPL-3.0"))

        assertEquals(EngineReadiness.BLOCKED, warp.readiness)
        assertFalse(warp.selectable)
        assertTrue(warp.statusDetail.contains("SDK/API"))
    }

    @Test
    fun explicitBlockReasonOverridesAnOtherwiseRegisteredAdapter() {
        val catalog = EngineCapabilityCatalog.build(
            adapters = listOf(FakeAdapter(EngineSlot.WIREGUARD)),
            blockedReasons = mapOf(EngineSlot.WIREGUARD to "operator block")
        )
        val entry = catalog.single { it.slot == EngineSlot.WIREGUARD }
        assertEquals(EngineReadiness.BLOCKED, entry.readiness)
        assertFalse(entry.selectable)
    }

}
