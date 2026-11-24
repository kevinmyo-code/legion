package com.kevin.midnightai.link

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * Pairing key agreement (HU-1 T3; spec section 3 of `.legion/reports/headunit-link-protocol-1120.md`):
 * ECDH on P-256 (secp256r1) with uncompressed 65-byte public keys, then HKDF-SHA256 into the link
 * key, the hand-over key and the 6-digit check code. P-256, not X25519: it is in every Android
 * release the unit could run (X25519 key agreement is not), and in the plain JDK for the tests.
 */
object PairingCrypto {
    const val PUBLIC_KEY_BYTES = 65
    const val SECRET_BYTES = 16
    /** QR version and hello version: both 1, both bound into the transcript (review L2). */
    const val QR_VERSION: Byte = 1
    const val HELLO_VERSION: Byte = 1
    private val PROTOCOL = "MNAI-PAIR".toByteArray(Charsets.US_ASCII)

    // secp256r1 domain, for the on-curve check of a peer's key (y^2 = x^3 - 3x + b mod p).
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** 0x04 || X(32) || Y(32). */
    fun encode(publicKey: ECPublicKey): ByteArray {
        fun fixed(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            val out = ByteArray(32)
            val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
            src.copyInto(out, 32 - src.size)
            return out
        }
        return byteArrayOf(4) + fixed(publicKey.w.affineX) + fixed(publicKey.w.affineY)
    }

    /** A peer's 65-byte key, or null if it is not a valid point on P-256. */
    fun decode(bytes: ByteArray, template: ECPublicKey): ECPublicKey? {
        if (bytes.size != PUBLIC_KEY_BYTES || bytes[0] != 4.toByte()) return null
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        if (x >= P || y >= P) return null
        val lhs = y.modPow(BigInteger.valueOf(2), P)
        val rhs = x.modPow(BigInteger.valueOf(3), P).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
        if (lhs != rhs) return null
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), template.params)) as ECPublicKey
        }.getOrNull()
    }

    fun sharedSecret(own: PrivateKey, peer: ECPublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(own); doPhase(peer, true) }.generateSecret()

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** The key that proves a peer saw the QR (the QR's one-time secret, never sent on the wire). */
    fun qrKey(secret: ByteArray): ByteArray = LinkCrypto.hkdf(secret, ByteArray(0), ascii("MNAI-PAIR qr v1"))

    /** The key derivation's salt, separated from [qrKey] by its own label (review L3). */
    fun saltKey(secret: ByteArray): ByteArray = LinkCrypto.hkdf(secret, ByteArray(0), ascii("MNAI-PAIR salt v1"))

    /** "MNAI-PAIR" || QR version || hello version || unit key || phone key (spec 3.3). */
    fun transcript(unitPk: ByteArray, phonePk: ByteArray): ByteArray =
        PROTOCOL + byteArrayOf(QR_VERSION, HELLO_VERSION) + unitPk + phonePk

    fun helloMac(qrKey: ByteArray, unitPk: ByteArray, phonePk: ByteArray): ByteArray =
        LinkCrypto.hmacSha256(qrKey, ascii("MNAI-PAIR hello v1") + byteArrayOf(HELLO_VERSION) + unitPk + phonePk)

    /** Key confirmation in the ack (spec 3.4): proves the unit derived the same master. */
    fun unitConfirm(confirmKey: ByteArray, transcript: ByteArray): ByteArray =
        LinkCrypto.hmacSha256(confirmKey, ascii("MNAI-PAIR unit confirm v1") + transcript)

    /** Everything the agreement yields. toString never prints key material or the digits. */
    class Keys(val link: ByteArray, val handover: ByteArray, val confirm: ByteArray, val checkCode: String) {
        fun wipe() {
            link.fill(0)
            handover.fill(0)
            confirm.fill(0)
        }

        override fun toString(): String = "PairingCrypto.Keys(redacted)"
    }

    fun derive(shared: ByteArray, secret: ByteArray, transcript: ByteArray): Keys {
        val info = ascii("MNAI-PAIR keys v1") + MessageDigest.getInstance("SHA-256").digest(transcript)
        val salt = saltKey(secret)
        val master = LinkCrypto.hkdf(shared, salt, info)
        salt.fill(0)
        val link = LinkCrypto.hkdf(master, ByteArray(0), ascii("MNAI-PAIR link v1"))
        val handover = LinkCrypto.hkdf(master, ByteArray(0), ascii("MNAI-PAIR handover v1"))
        val confirm = LinkCrypto.hkdf(master, ByteArray(0), ascii("MNAI-PAIR confirm v1"))
        val check = LinkCrypto.hkdf(master, ByteArray(0), ascii("MNAI-PAIR check v1"), 4)
        master.fill(0)
        val n = ((check[0].toLong() and 0xff) shl 24) or ((check[1].toLong() and 0xff) shl 16) or
            ((check[2].toLong() and 0xff) shl 8) or (check[3].toLong() and 0xff)
        return Keys(link, handover, confirm, (n % 1_000_000L).toString().padStart(6, '0'))
    }
}
