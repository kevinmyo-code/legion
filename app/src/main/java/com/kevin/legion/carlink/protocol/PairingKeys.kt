package com.kevin.legion.carlink.protocol

import com.kevin.legion.carlink.protocol.LinkCrypto.ascii
import com.kevin.legion.carlink.protocol.LinkCrypto.hkdf

/**
 * Section 3.3's key schedule for one pairing session. Holds secrets: not a data class, no toString of
 * its fields, and [wipe] zeroes every array once the session ends.
 */
class PairingKeys private constructor(
    val qrKey: ByteArray,
    val saltKey: ByteArray,
    val transcript: ByteArray,
    val master: ByteArray,
    val linkKey: ByteArray,
    val handKey: ByteArray,
    val confirmKey: ByteArray,
    /** The six digits the phone shows, zero-padded. Never logged. */
    val check: String,
) {
    override fun toString(): String = "PairingKeys(redacted)"

    fun wipe() {
        listOf(qrKey, saltKey, master, linkKey, handKey, confirmKey).forEach { it.fill(0) }
    }

    /** `HMAC(qrKey, "MNAI-PAIR hello v1" || 0x01 || unitPk || phonePk)`, message 1. */
    fun helloMac(unitPk: ByteArray, phonePk: ByteArray): ByteArray =
        LinkCrypto.hmac(qrKey, ascii("MNAI-PAIR hello v1") + byteArrayOf(HELLO_VERSION) + unitPk + phonePk)

    /** `HMAC(confirmKey, "MNAI-PAIR unit confirm v1" || transcript)`, the `kc` in message 2. */
    fun expectedKc(): ByteArray = LinkCrypto.hmac(confirmKey, ascii("MNAI-PAIR unit confirm v1") + transcript)

    companion object {
        const val QR_VERSION: Byte = 1
        const val HELLO_VERSION: Byte = 1
        private const val CHECK_LEN = 4
        private const val CHECK_MOD = 1_000_000L
        private const val CHECK_DIGITS = 6
        private const val BYTE_MASK = 0xFF
        private const val BITS = 8

        fun transcript(unitPk: ByteArray, phonePk: ByteArray): ByteArray =
            ascii("MNAI-PAIR") + byteArrayOf(QR_VERSION, HELLO_VERSION) + unitPk + phonePk

        /** [z] is ECDH's shared X coordinate; the caller zeroes it. */
        fun derive(secret: ByteArray, unitPk: ByteArray, phonePk: ByteArray, z: ByteArray): PairingKeys {
            val empty = ByteArray(0)
            val qrKey = hkdf(secret, empty, ascii("MNAI-PAIR qr v1"))
            val saltKey = hkdf(secret, empty, ascii("MNAI-PAIR salt v1"))
            val transcript = transcript(unitPk, phonePk)
            val master = hkdf(z, saltKey, ascii("MNAI-PAIR keys v1") + LinkCrypto.sha256(transcript))
            val checkRaw = hkdf(master, empty, ascii("MNAI-PAIR check v1"), CHECK_LEN)
            var u32 = 0L
            for (b in checkRaw) u32 = (u32 shl BITS) or (b.toInt() and BYTE_MASK).toLong()
            checkRaw.fill(0)
            return PairingKeys(
                qrKey = qrKey,
                saltKey = saltKey,
                transcript = transcript,
                master = master,
                linkKey = hkdf(master, empty, ascii("MNAI-PAIR link v1")),
                handKey = hkdf(master, empty, ascii("MNAI-PAIR handover v1")),
                confirmKey = hkdf(master, empty, ascii("MNAI-PAIR confirm v1")),
                check = (u32 % CHECK_MOD).toString().padStart(CHECK_DIGITS, '0'),
            )
        }
    }
}
