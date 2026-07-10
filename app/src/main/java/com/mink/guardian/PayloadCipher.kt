package com.mink.guardian

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the guardian's persisted payloads at rest. */
interface PayloadCipher {
    /** Returns the storable representation of [plain], or null if encryption failed. */
    fun encrypt(plain: String): String?

    /** Returns the plaintext for a stored [payload], or null if it cannot be read. */
    fun decrypt(payload: String): String?

    companion object {
        /** Identity cipher, for unit tests and any device where the Keystore is unusable. */
        val None: PayloadCipher = object : PayloadCipher {
            override fun encrypt(plain: String): String? = plain
            override fun decrypt(payload: String): String? = payload
        }
    }
}

/** Wire-format prefix marking a value as [KeystorePayloadCipher] ciphertext. */
private const val ENC_PREFIX = "ENC1:"

/** GCM initialization-vector length, in bytes. */
private const val GCM_IV_BYTES = 12

/** GCM authentication-tag length, in bits. */
private const val GCM_TAG_BITS = 128

/** AES key size, in bits. */
private const val AES_KEY_BITS = 256

/**
 * A payload whose plaintext does not carry the [ENC_PREFIX] marker and is
 * therefore a legacy value written before encryption at rest existed. Such a
 * value is returned from [PayloadCipher.decrypt] as-is and re-encrypted on the
 * next write.
 */
internal fun isLegacyPayload(s: String): Boolean = !s.startsWith(ENC_PREFIX)

/**
 * [PayloadCipher] backed by an Android Keystore AES-GCM key.
 *
 * The 256-bit key is generated on first use under [alias] in the
 * `AndroidKeyStore`, never leaves the TEE/StrongBox, and is cached after first
 * load. It requires no user authentication so that background sweeps and the
 * WorkManager backstop run with the device locked.
 *
 * Wire format is `"ENC1:" + Base64(iv || ciphertext)` with a fresh random 12-byte
 * IV per encryption. On [decrypt], a value without the `ENC1:` prefix is treated
 * as legacy plaintext and returned unchanged (it re-encrypts on the next write);
 * any authentication failure, key invalidation, or decode error yields null.
 * No method ever throws — a Keystore that cannot be used degrades to null.
 */
class KeystorePayloadCipher(private val alias: String = "mink.guardian.v1") : PayloadCipher {

    @Volatile
    private var cachedKey: SecretKey? = null

    override fun encrypt(plain: String): String? = runCatching {
        val key = secretKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
    }.getOrNull()

    override fun decrypt(payload: String): String? {
        if (isLegacyPayload(payload)) return payload
        return runCatching {
            val key = secretKey() ?: return null
            val combined = Base64.decode(payload.substring(ENC_PREFIX.length), Base64.NO_WRAP)
            if (combined.size <= GCM_IV_BYTES) return null
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    /** Loads (or lazily generates) the Keystore key, caching it; null if the Keystore is unusable. */
    private fun secretKey(): SecretKey? {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return it }
            val key = runCatching { loadOrCreateKey() }.getOrNull()
            cachedKey = key
            key
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_BITS)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
