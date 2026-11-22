package ai.molido.vpn.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts pinned public keys and monotonic version counters at rest with an
 * Android Keystore AES-GCM key. Existing preference names and ciphertext format
 * are preserved for upgrade compatibility. Initial key pinning is provisioned
 * out of band; decryption never silently creates a replacement key.
 */
class AndroidSecureTrustStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun pinControlPlanePublicKey(encodedX509: ByteArray) = pinOnce(CONTROL_KEY, encodedX509)

    @Synchronized
    fun pinUpdatePublicKey(encodedX509: ByteArray) = pinOnce(UPDATE_KEY, encodedX509)

    @Synchronized
    fun loadControlPlanePublicKey(): PublicKey = decodeAndClear(CONTROL_KEY)

    @Synchronized
    fun loadUpdatePublicKey(): PublicKey = decodeAndClear(UPDATE_KEY)

    @Synchronized
    fun currentConfigurationRevision(): Long = readCounter(CONFIG_REVISION)

    @Synchronized
    fun advanceConfigurationRevision(revision: Long) {
        advanceCounter(CONFIG_REVISION, revision)
    }

    @Synchronized
    fun currentInstalledVersionCode(): Long = readCounter(INSTALLED_VERSION)

    @Synchronized
    fun advanceInstalledVersionCode(versionCode: Long) {
        advanceCounter(INSTALLED_VERSION, versionCode)
    }

    private fun pinOnce(name: String, encoded: ByteArray) {
        require(encoded.isNotEmpty() && encoded.size <= MAX_PUBLIC_KEY_BYTES)
        val existing = preferences.getString(name, null)
        if (existing != null) {
            val ciphertext = Base64.decode(existing, Base64.NO_WRAP)
            val prior = try {
                decrypt(ciphertext)
            } finally {
                ciphertext.fill(0)
            }
            try {
                require(prior.contentEquals(encoded)) { "Pinned trust key replacement is forbidden" }
            } finally {
                prior.fill(0)
            }
            return
        }
        val encrypted = encrypt(encoded)
        try {
            check(preferences.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
                "Unable to persist pinned trust key"
            }
        } finally {
            encrypted.fill(0)
        }
    }

    private fun decodeAndClear(name: String): PublicKey {
        val encoded = readEncrypted(name)
        return try {
            decodePublicKey(encoded)
        } finally {
            encoded.fill(0)
        }
    }

    private fun readEncrypted(name: String): ByteArray {
        val stored = preferences.getString(name, null)
            ?: throw IllegalStateException("Required trust key is not provisioned")
        val ciphertext = Base64.decode(stored, Base64.NO_WRAP)
        return try {
            decrypt(ciphertext)
        } finally {
            ciphertext.fill(0)
        }
    }

    private fun decodePublicKey(encoded: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    private fun readCounter(name: String): Long {
        val stored = preferences.getString(name, null) ?: return 0L
        val ciphertext = Base64.decode(stored, Base64.NO_WRAP)
        val bytes = try {
            decrypt(ciphertext)
        } finally {
            ciphertext.fill(0)
        }
        return try {
            require(bytes.size == java.lang.Long.BYTES) { "Stored monotonic counter is malformed" }
            ByteBuffer.wrap(bytes).long.also { require(it >= 0) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun advanceCounter(name: String, value: Long) {
        require(value > 0)
        val current = readCounter(name)
        require(value > current) { "Rollback or duplicate revision rejected" }
        val plaintext = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(value).array()
        val encrypted = try {
            encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        try {
            check(preferences.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
                "Unable to persist monotonic counter"
            }
        } finally {
            encrypted.fill(0)
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(plain)
        return try {
            cipher.iv + encrypted
        } finally {
            encrypted.fill(0)
        }
    }

    private fun decrypt(encoded: ByteArray): ByteArray {
        require(encoded.size > GCM_IV_BYTES + GCM_TAG_BYTES) { "Stored trust data is malformed" }
        val iv = encoded.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = encoded.copyOfRange(GCM_IV_BYTES, encoded.size)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                encryptionKey(createIfMissing = false),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            return cipher.doFinal(ciphertext)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun encryptionKey(createIfMissing: Boolean = true): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getKey(ENCRYPTION_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        check(createIfMissing) {
            "Android Keystore trust key is missing; refusing to replace it during decryption"
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ENCRYPTION_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "molido_trust_store"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ENCRYPTION_KEY_ALIAS = "molido.trust.aesgcm.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16
        const val MAX_PUBLIC_KEY_BYTES = 4096
        const val CONTROL_KEY = "control_plane_public_key"
        const val UPDATE_KEY = "update_public_key"
        const val CONFIG_REVISION = "configuration_revision"
        const val INSTALLED_VERSION = "installed_version_code"
    }
}
