package ai.molido.vpn.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import javax.crypto.SecretKey
import android.util.Base64

@RunWith(AndroidJUnit4::class)
@SmallTest
class AndroidSecureProfileStoreTest {
    private lateinit var context: Context
    private lateinit var store: AndroidSecureProfileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AndroidSecureProfileStore(context)
        store.revokeConsentAndDelete()
    }

    @After
    fun tearDown() {
        store.revokeConsentAndDelete()
    }

    @Test
    fun explicitConsentEncryptsAndRoundTripsThroughAndroidKeystore() {
        val profile = "[Interface]\\nPrivateKey = integration-test-private-key\\n[Peer]".toByteArray()
        store.saveProfileWithConsent(profile, userConfirmed = true)

        assertTrue(store.hasUserConsent())
        assertTrue(keyStore().containsAlias(KEY_ALIAS))
        val key = keyStore().getKey(KEY_ALIAS, null) as SecretKey
        assertNull("Android Keystore key material must not be exportable", key.encoded)
        val restored = AndroidSecureProfileStore(context).loadProfile()
        assertArrayEquals(profile, restored)
        restored?.fill(0)
        profile.fill(0)
    }

    @Test
    fun refusesPersistenceWithoutExplicitConsent() {
        val profile = "sensitive-profile".toByteArray()
        try {
            store.saveProfile(profile)
            fail("Persistence must require prior consent")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse(store.hasUserConsent())
        assertFalse(keyStore().containsAlias(KEY_ALIAS))
        profile.fill(0)
    }

    @Test
    fun rejectsNegativeConsentAndDoesNotWriteAnything() {
        try {
            store.saveProfileWithConsent("profile".toByteArray(), userConfirmed = false)
            fail("False confirmation must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertFalse(store.hasUserConsent())
        assertFalse(keyStore().containsAlias(KEY_ALIAS))
    }

    @Test
    fun encryptionValidationFailureDoesNotPersistConsentOrCiphertext() {
        try {
            store.saveProfileWithConsent(ByteArray(0), userConfirmed = true)
            fail("Empty profiles must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
        assertFalse(store.hasUserConsent())
        assertNull(preferences().getString(PROFILE_KEY, null))
        assertFalse(keyStore().containsAlias(KEY_ALIAS))
    }

    @Test
    fun tamperedCiphertextFailsAuthenticationAndIsNotReturned() {
        store.saveProfileWithConsent("known-profile".toByteArray(), userConfirmed = true)
        val encoded = preferences().getString(PROFILE_KEY, null)!!
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        preferences().edit().putString(PROFILE_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        bytes.fill(0)

        try {
            store.loadProfile()
            fail("Modified GCM ciphertext must fail authentication")
        } catch (_: javax.crypto.AEADBadTagException) {
            // expected
        }
    }

    @Test
    fun revocationRemovesCiphertextConsentAndOnlyTheDedicatedKeystoreKey() {
        store.saveProfileWithConsent("known-profile".toByteArray(), userConfirmed = true)
        assertTrue(keyStore().containsAlias(KEY_ALIAS))

        store.revokeConsentAndDelete()

        assertFalse(store.hasUserConsent())
        assertNull(preferences().getString(PROFILE_KEY, null))
        assertFalse(keyStore().containsAlias(KEY_ALIAS))
        assertNull(store.loadProfile())
    }

    @Test
    fun committedProfileSurvivesStoreRecreationWithoutEnablingAutoConnect() {
        val profile = "persisted-profile".toByteArray()
        store.saveProfileWithConsent(profile, userConfirmed = true)
        val recreatedStore = AndroidSecureProfileStore(context)
        val restored = recreatedStore.loadProfile()
        assertArrayEquals(profile, restored)
        // Storage is deliberately passive: this test does not start a VpnService.
        restored?.fill(0)
        profile.fill(0)
    }


    @Test
    fun persistedPreferencesContainCiphertextRatherThanPlainProfile() {
        val marker = "PROFILE-PLAINTEXT-MUST-NOT-BE-STORED"
        store.saveProfileWithConsent(marker.toByteArray(), userConfirmed = true)
        val persisted = preferences().getString(PROFILE_KEY, null)!!
        assertFalse(persisted.contains(marker))
        assertFalse(persisted.contains("PrivateKey"))
    }

    @Test
    fun missingKeystoreKeyFailsClosedWithoutGeneratingReplacement() {
        store.saveProfileWithConsent("known-profile".toByteArray(), userConfirmed = true)
        keyStore().deleteEntry(KEY_ALIAS)

        try {
            store.loadProfile()
            fail("A missing decryption key must not be silently regenerated")
        } catch (_: IllegalStateException) {
            // expected: stored ciphertext is unrecoverable without its original key
        }
        assertFalse(keyStore().containsAlias(KEY_ALIAS))
        assertTrue(store.hasUserConsent())
    }

    @Test
    fun truncatedStoredCiphertextIsRejected() {
        store.saveProfileWithConsent("known-profile".toByteArray(), userConfirmed = true)
        preferences().edit()
            .putString(PROFILE_KEY, Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP))
            .commit()
        try {
            store.loadProfile()
            fail("Truncated ciphertext must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
\n    private fun preferences() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val PREFERENCES = "molido_secure_profile_v1"
        const val PROFILE_KEY = "encrypted_profile"
        const val KEY_ALIAS = "ai.molido.vpn.profile.aesgcm.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
