package ai.molido.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicySettingsStoreTest {
    private class MemoryPreferences {
        val values = mutableMapOf<String, String>()
        fun store() = NetworkPolicySettingsStore(
            readString = { key -> values[key] },
            writeString = { key, value -> values[key] = value; true }
        )
    }

    @Test
    fun savesAndRestoresPolicyAcrossStoreInstances() {
        val preferences = MemoryPreferences()
        assertTrue(preferences.store().save(NetworkPolicy.WIFI_ONLY))
        assertEquals(NetworkPolicy.WIFI_ONLY, preferences.store().load())
    }

    @Test
    fun missingOrInvalidValueFallsBackToAnyValidated() {
        val preferences = MemoryPreferences()
        assertEquals(NetworkPolicy.ANY_VALIDATED, preferences.store().load())
        preferences.values[NetworkPolicySettingsStore.POLICY_KEY] = "UNRECOGNIZED_POLICY"
        assertEquals(NetworkPolicy.ANY_VALIDATED, preferences.store().load())
    }

    @Test
    fun saveFailureIsReported() {
        val store = NetworkPolicySettingsStore({ null }, { _, _ -> false })
        assertFalse(store.save(NetworkPolicy.UNMETERED_ONLY))
    }
}
