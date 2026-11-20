package com.kevin.legion.carlink.protocol

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/** The phone's ephemeral pairing key pair. Not a data class: a generated toString must never print a key. */
class PhoneKeyPair internal constructor(internal val private: PrivateKey, val publicBytes: ByteArray)

/**
 * P-256 as section 2 "EC" fixes it: public keys uncompressed, 65 bytes `04 || X || Y`; a received key
 * is checked for length, prefix, X and Y below p, and the curve equation, and refused otherwise.
 * Z is the 32-byte X coordinate (what JCA's ECDH returns).
 */
object P256 {
    const val PUBLIC_LEN = 65
    private const val COORD_LEN = 32
    private const val UNCOMPRESSED: Byte = 0x04

    /**
     * Curve parameters taken from a generated key pair, not from `AlgorithmParameters("EC")`, which
     * older Android providers do not offer.
     */
    private val params: ECParameterSpec by lazy {
        (newPair().public as ECPublicKey).params
    }

    private fun newPair(random: SecureRandom = SecureRandom()) =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()

    fun generate(random: SecureRandom = SecureRandom()): PhoneKeyPair {
        val pair = newPair(random)
        return PhoneKeyPair(pair.private, encode(pair.public as ECPublicKey))
    }

    /** Test seam for the known-answer vectors: a key pair from a fixed scalar and its known public bytes. */
    internal fun fromScalar(scalarHex: String, publicBytes: ByteArray): PhoneKeyPair {
        val spec = ECPrivateKeySpec(BigInteger(scalarHex, 16), params)
        return PhoneKeyPair(KeyFactory.getInstance("EC").generatePrivate(spec), publicBytes.copyOf())
    }

    private fun encode(key: ECPublicKey): ByteArray =
        byteArrayOf(UNCOMPRESSED) + fixed(key.w.affineX) + fixed(key.w.affineY)

    private fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == COORD_LEN -> raw
            raw.size > COORD_LEN -> raw.copyOfRange(raw.size - COORD_LEN, raw.size)
            else -> ByteArray(COORD_LEN - raw.size) + raw
        }
    }

    /** The checked public key, or null when [bytes] is not a valid uncompressed P-256 point. */
    fun parsePublic(bytes: ByteArray): PublicKey? {
        if (bytes.size != PUBLIC_LEN || bytes[0] != UNCOMPRESSED) return null
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORD_LEN))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORD_LEN, PUBLIC_LEN))
        val curve = params.curve
        val p = (curve.field as ECFieldFp).p
        if (x >= p || y >= p) return null
        val lhs = y.multiply(y).mod(p)
        val rhs = x.pow(3).add(curve.a.multiply(x)).add(curve.b).mod(p)
        if (lhs != rhs) return null
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
    }

    /** Z = ECDH(own private, peer public): the shared X coordinate, 32 bytes. */
    fun agree(own: PhoneKeyPair, peer: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(own.private)
        ka.doPhase(peer, true)
        return ka.generateSecret()
    }
}
