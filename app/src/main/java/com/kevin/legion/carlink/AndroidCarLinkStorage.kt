package com.kevin.legion.carlink

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The car link's own Android Keystore key (AES-256-GCM). Unlike [com.kevin.legion.ai.KeyVault] there
 * is no plaintext fallback: a failure is null, and [CarLinkStore] then writes nothing. Logs only the
 * exception's class, never a message that could carry data. Blob: 12-byte IV || ciphertext+tag.
 */
class KeystoreSecretBox : SecretBox {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == IV_LEN)
        iv + cipher.doFinal(plain)
    } catch (e: GeneralSecurityException) {
        failed("seal", e)
    } catch (e: java.io.IOException) {
        failed("seal", e)
    } catch (e: IllegalStateException) {
        failed("seal", e)
    }

    override fun open(blob: ByteArray): ByteArray? = try {
        require(blob.size > IV_LEN)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN))
        cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    } catch (e: GeneralSecurityException) {
        failed("open", e)
    } catch (e: java.io.IOException) {
        failed("open", e)
    } catch (e: IllegalArgumentException) {
        failed("open", e)
    }

    private fun failed(what: String, e: Exception): ByteArray? {
        Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
        return null
    }

    private companion object {
        const val TAG = "CarLinkKeystore"
        const val ALIAS = "legion_car_link_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}

/** `car_link.xml`, written with one editor and `commit()` so a write lands whole or not at all. */
class PrefsRecordFile(context: Context) : RecordFile {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(CarLinkStore.FILE, Context.MODE_PRIVATE)

    override fun read(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun writeAll(values: Map<String, String?>): Boolean {
        val editor = prefs.edit()
        for ((k, v) in values) if (v == null) editor.remove(k) else editor.putString(k, v)
        return editor.commit()
    }
}
