package com.kevin.midnightai.link

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The car link's symmetric primitives (HU-1 T3/T4; wire spec in
 * `.legion/reports/headunit-link-protocol-1120.md`, mirrored by the phone, which shares no code
 * with this app). Plain JDK crypto, so every byte is JVM-tested with fixed vectors.
 *
 * - HKDF-SHA256 (RFC 5869) derives every key from the pairing secret material.
 * - AES-256-GCM with a 96-bit nonce and a 128-bit tag seals every message; the caller supplies the
 *   nonce (direction prefix + counter, never reused under one key) and the associated data.
 *
 * Nothing here logs, and no function puts key material in an exception message.
 */
object LinkCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    /** RFC 5869 HKDF-SHA256: extract with [salt] (empty = zeros), expand to [length] bytes with [info]. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = KEY_BYTES): ByteArray {
        require(length in 1..255 * 32) { "bad HKDF length" }
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val n = minOf(previous.size, length - written)
            previous.copyInto(out, written, 0, n)
            written += n
            counter++
        }
        prk.fill(0)
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** AES-256-GCM seal: ciphertext followed by the 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES) { "bad key or nonce size" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    /** AES-256-GCM open; null on ANY failure (wrong key, tampered bytes, bad sizes). Never throws. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (key.size != KEY_BYTES || nonce.size != NONCE_BYTES || sealed.size < TAG_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Constant-time equality for MACs and check values. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** A 12-byte nonce: 4-byte direction label then a big-endian 64-bit counter. */
    fun nonce(direction: ByteArray, counter: Long): ByteArray {
        require(direction.size == 4) { "direction is 4 bytes" }
        val n = ByteArray(NONCE_BYTES)
        direction.copyInto(n, 0)
        for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte()
        return n
    }
}
