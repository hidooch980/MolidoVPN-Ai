package ai.molido.vpn.core

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.KeyStore
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
@SmallTest
class AndroidSecureTrustStoreTest {
    private lateinit var context: Context
    private lateinit var store: AndroidSecureTrustStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences().edit().clear().commit()
        keyStore().deleteEntry(KEY_ALIAS)
        store = AndroidSecureTrustStore(context)
    }

    @After
    fun tearDown() {
        preferences().edit().clear().commit()
        keyStore().deleteEntry(KEY_ALIAS)
    }

    @Test
    fun pinsAndLoadsControlPlaneAndUpdateKeysWithoutAllowingReplacement() {
        val first = generatePublicKey()
        val second = generatePublicKey()
        store.pinControlPlanePublicKey(first.encoded)
        store.pinUpdatePublicKey(second.encoded)

        assertArrayEquals(first.encoded, store.loadControlPlanePublicKey().encoded)
        assertArrayEquals(second.encoded, store.loadUpdatePublicKey().encoded)

        try {
            store.pinControlPlanePublicKey(second.encoded)
            fail("A pinned control-plane key must not be silently replaced")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertArrayEquals(first.encoded, store.loadControlPlanePublicKey().encoded)
    }

    @Test
    fun monotonicCountersRejectDuplicateAndRollbackValues() {
        assertEquals(0L, store.currentConfigurationRevision())
        assertEquals(0L, store.currentInstalledVersionCode())

        store.advanceConfigurationRevision(4L)
        store.advanceInstalledVersionCode(12L)

        assertEquals(4L, store.currentConfigurationRevision())
        assertEquals(12L, store.currentInstalledVersionCode())
        assertRejected { store.advanceConfigurationRevision(4L) }
        assertRejected { store.advanceConfigurationRevision(3L) }
        assertRejected { store.advanceInstalledVersionCode(12L) }
        assertRejected { store.advanceInstalledVersionCode(11L) }
    }

    @Test
    fun missingKeystoreKeyFailsClosedWithoutGeneratingReplacement() {
        store.pinControlPlanePublicKey(generatePublicKey().encoded)
        assertTrue(keyStore().containsAlias(KEY_ALIAS))
        keyStore().deleteEntry(KEY_ALIAS)

        try {
            store.loadControlPlanePublicKey()
            fail("A missing decryption key must not be silently regenerated")
        } catch (_: IllegalStateException) {
            // expected: existing ciphertext cannot be decrypted without its original key
        }

        assertFalse(keyStore().containsAlias(KEY_ALIAS))
        assertNotNull(preferences().getString(CONTROL_KEY, null))
    }

    @Test
    fun tamperedPinnedKeyCiphertextFailsGcmAuthentication() {
        store.pinControlPlanePublicKey(generatePublicKey().encoded)
        val encoded = preferences().getString(CONTROL_KEY, null)!!
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        preferences().edit().putString(CONTROL_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        bytes.fill(0)

        try {
            store.loadControlPlanePublicKey()
            fail("Modified GCM ciphertext must fail authentication")
        } catch (_: AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun existingPreferenceNamesAndCiphertextRemainReadableAcrossStoreInstances() {
        val key = generatePublicKey()
        store.pinUpdatePublicKey(key.encoded)
        store.advanceInstalledVersionCode(7L)

        val recreated = AndroidSecureTrustStore(context)
        assertArrayEquals(key.encoded, recreated.loadUpdatePublicKey().encoded)
        assertEquals(7L, recreated.currentInstalledVersionCode())
    }

    private fun generatePublicKey() =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            fail("Duplicate or rollback counter value must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun preferences() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val PREFERENCES = "molido_trust_store"
        const val CONTROL_KEY = "control_plane_public_key"
        const val KEY_ALIAS = "molido.trust.aesgcm.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
