package com.kevin.legion.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts short secrets (the BYO Gemini key) with an AES/GCM key held in the
 * Android Keystore, so the plaintext never sits in SharedPrefs. Used instead of
 * androidx.security-crypto (EncryptedSharedPreferences), which is deprecated.
 *
 * Every function returns null on ANY failure rather than throwing — cheap head
 * units ship flaky keymaster HALs, and a broken Keystore must degrade to the
 * caller's fallback (plaintext storage), never crash or brick key entry.
 *
 * Blob format: Base64(NO_WRAP) of [ivLen: 1 byte][iv][ciphertext+tag].
 */
object KeyVault {
    private const val TAG = "KeyVault"
    private const val ALIAS = "nightrunner_vault"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Encrypts [plain] into a self-contained Base64 blob, or null if the Keystore is broken. */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val blob = ByteArray(1 + iv.size + ct.size)
        blob[0] = iv.size.toByte()
        iv.copyInto(blob, 1)
        ct.copyInto(blob, 1 + iv.size)
        Base64.encodeToString(blob, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "encrypt failed: ${e.message}")
        null
    }

    /** Decrypts a blob produced by [encrypt], or null (wrong key after OS wipe, corrupt blob, broken HAL). */
    fun decrypt(blob: String): String? = try {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val ivLen = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val ct = bytes.copyOfRange(1 + ivLen, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "decrypt failed: ${e.message}")
        null
    }
}
