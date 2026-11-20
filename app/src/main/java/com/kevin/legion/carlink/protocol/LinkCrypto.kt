package com.kevin.legion.carlink.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * The car link's primitives, protocol section 2, both ends identical. Pure JVM: no Android type, so
 * the known-answer vectors run without Robolectric.
 *
 * Stateless functions only. Every key is a [ByteArray] the caller owns and zeroes; nothing here
 * keeps one or prints one.
 */
object LinkCrypto {
    private const val KEY_LEN = 32
    private const val TAG_BITS = 128
    private const val NONCE_DIR_LEN = 4
    const val NONCE_LEN = 12

    /** RFC 5869 HKDF-SHA256. An empty salt is 32 zero bytes (section 2). [length] at most 32. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = KEY_LEN): ByteArray {
        require(length in 1..KEY_LEN) { "one HMAC block only" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(KEY_LEN) else salt, ikm)
        try {
            val okm = hmac(prk, info + byteArrayOf(1))
            return if (length == KEY_LEN) okm else okm.copyOf(length).also { okm.fill(0) }
        } finally {
            prk.fill(0)
        }
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** Constant-time comparison (a MAC check must not leak how many leading bytes matched). */
    fun sameBytes(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** NONCE(dir4, ctr): 4 ASCII bytes, then [counter] as unsigned 64-bit big-endian. */
    fun nonce(dir4: String, counter: Long): ByteArray {
        val dir = dir4.toByteArray(Charsets.US_ASCII)
        require(dir.size == NONCE_DIR_LEN) { "direction is 4 ASCII bytes" }
        return ByteBuffer.allocate(NONCE_LEN).put(dir).putLong(counter).array()
    }

    /** AES-256-GCM, 128-bit tag; ciphertext || tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    /** The plaintext, or null when the tag does not verify (tampered, wrong key, wrong nonce or AAD). */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        gcm(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)
    } catch (_: AEADBadTagException) {
        null
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == KEY_LEN && nonce.size == NONCE_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher
    }

    /** RFC 4648 standard alphabet, padded (section 2 B64). */
    fun b64(bytes: ByteArray): String = Base64.Default.encode(bytes)

    /** RFC 4648 URL-safe alphabet, padded (section 2 B64URL). */
    fun b64url(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes)

    /** Strict: padding required, no other alphabet, no whitespace, zero pad bits. Null on any fault. */
    fun unb64(text: String): ByteArray? = strictDecode(Base64.Default, text)

    fun unb64url(text: String): ByteArray? = strictDecode(Base64.UrlSafe, text)

    private fun strictDecode(codec: Base64, text: String): ByteArray? = try {
        val bytes = codec.decode(text)
        // Re-encoding must reproduce the input exactly: refuses any second spelling of the same bytes.
        if (codec.encode(bytes) == text) bytes else null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
