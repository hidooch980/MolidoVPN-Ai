package ai.molido.vpn.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Opt-in, device-local profile persistence.
 *
 * The profile is encrypted before it reaches SharedPreferences. The AES key is
 * generated inside Android Keystore and is never exported. This class does not
 * schedule work or initiate VPN connections.
 */
class AndroidSecureProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun hasUserConsent(): Boolean = preferences.getBoolean(CONSENT_KEY, false)

    /** Call only after an explicit affirmative user action in the UI. */
    @Synchronized
    fun grantUserConsent(userConfirmed: Boolean) {
        require(userConfirmed) { "Explicit user confirmation is required" }
        check(preferences.edit().putBoolean(CONSENT_KEY, true).commit()) {
            "Unable to persist profile-storage consent"
        }
    }

    /**
     * Saves a bounded profile only after opt-in. Caller must pass a private copy
     * and clear that copy after this call returns.
     */
    @Synchronized
    fun saveProfile(profile: ByteArray) {
        check(hasUserConsent()) { "Profile persistence has not been approved by the user" }
        persistEncryptedProfile(profile, includeConsent = false)
    }

    /**
     * Atomically stores explicit consent and the encrypted profile in one
     * SharedPreferences commit. Encryption completes before either value changes.
     * A failed encryption therefore cannot leave a consent-only partial state.
     */
    @Synchronized
    fun saveProfileWithConsent(profile: ByteArray, userConfirmed: Boolean) {
        require(userConfirmed) { "Explicit user confirmation is required" }
        persistEncryptedProfile(profile, includeConsent = true)
    }

    private fun persistEncryptedProfile(profile: ByteArray, includeConsent: Boolean) {
        require(profile.isNotEmpty() && profile.size <= MAX_PROFILE_BYTES) { "Invalid profile size" }
        val encrypted = encrypt(profile)
        try {
            val editor = preferences.edit()
                .putString(PROFILE_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            if (includeConsent) editor.putBoolean(CONSENT_KEY, true)
            check(editor.commit()) { "Unable to persist encrypted profile" }
        } finally {
            encrypted.fill(0)
        }
    }

    /** Returns a new plaintext buffer; caller must clear it after use. */
    @Synchronized
    fun loadProfile(): ByteArray? {
        if (!hasUserConsent()) return null
        val encoded = preferences.getString(PROFILE_KEY, null) ?: return null
        val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
        return try {
            decrypt(ciphertext)
        } finally {
            ciphertext.fill(0)
        }
    }

    /** Revokes consent, removes ciphertext, and deletes only this store's key. */
    @Synchronized
    fun revokeConsentAndDelete() {
        check(preferences.edit().clear().commit()) { "Unable to delete stored profile" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plain)
        return ByteBuffer.allocate(cipher.iv.size + encrypted.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
    }

    private fun decrypt(encoded: ByteArray): ByteArray {
        require(encoded.size > IV_BYTES + TAG_BYTES) { "Stored profile is malformed" }
        val iv = encoded.copyOfRange(0, IV_BYTES)
        val encrypted = encoded.copyOfRange(IV_BYTES, encoded.size)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            return cipher.doFinal(encrypted)
        } finally {
            iv.fill(0)
            encrypted.fill(0)
        }
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "molido_secure_profile_v1"
        const val CONSENT_KEY = "user_consent"
        const val PROFILE_KEY = "encrypted_profile"
        const val KEY_ALIAS = "ai.molido.vpn.profile.aesgcm.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
        const val MAX_PROFILE_BYTES = 64 * 1024
        val AAD = "molido-vpn-profile-v1".toByteArray(Charsets.UTF_8)
    }
}
