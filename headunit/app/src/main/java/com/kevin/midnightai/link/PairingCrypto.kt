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
    private val PROTOCOL = "MNAI-PAIR v1".toByteArray(Charsets.US_ASCII)

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

    /** The key that proves a peer saw the QR (the QR's one-time secret, never sent on the wire). */
    fun qrKey(secret: ByteArray): ByteArray =
        LinkCrypto.hkdf(secret, ByteArray(0), "MNAI-PAIR qr v1".toByteArray(Charsets.US_ASCII))

    /** "MNAI-PAIR v1" || unit key || phone key. */
    fun transcript(unitPk: ByteArray, phonePk: ByteArray): ByteArray = PROTOCOL + unitPk + phonePk

    fun helloMac(qrKey: ByteArray, unitPk: ByteArray, phonePk: ByteArray): ByteArray =
        LinkCrypto.hmacSha256(qrKey, "MNAI-PAIR hello v1".toByteArray(Charsets.US_ASCII) + unitPk + phonePk)

    fun ackMac(qrKey: ByteArray, transcript: ByteArray): ByteArray =
        LinkCrypto.hmacSha256(qrKey, "MNAI-PAIR ack v1".toByteArray(Charsets.US_ASCII) + transcript)

    /** Everything the agreement yields. toString never prints key material. */
    class Keys(val link: ByteArray, val handover: ByteArray, val checkCode: String) {
        fun wipe() {
            link.fill(0)
            handover.fill(0)
        }

        override fun toString(): String = "PairingCrypto.Keys(redacted)"
    }

    fun derive(shared: ByteArray, secret: ByteArray, transcript: ByteArray): Keys {
        val info = "MNAI-PAIR keys v1".toByteArray(Charsets.US_ASCII) +
            MessageDigest.getInstance("SHA-256").digest(transcript)
        val master = LinkCrypto.hkdf(shared, secret, info)
        val link = LinkCrypto.hkdf(master, ByteArray(0), "MNAI-PAIR link v1".toByteArray(Charsets.US_ASCII))
        val handover = LinkCrypto.hkdf(master, ByteArray(0), "MNAI-PAIR handover v1".toByteArray(Charsets.US_ASCII))
        val check = LinkCrypto.hkdf(master, ByteArray(0), "MNAI-PAIR check v1".toByteArray(Charsets.US_ASCII), 4)
        master.fill(0)
        val n = ((check[0].toLong() and 0xff) shl 24) or ((check[1].toLong() and 0xff) shl 16) or
            ((check[2].toLong() and 0xff) shl 8) or (check[3].toLong() and 0xff)
        return Keys(link, handover, (n % 1_000_000L).toString().padStart(6, '0'))
    }
}
