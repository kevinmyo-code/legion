// Guard clauses: the document says "refuse otherwise" at each check, and one early return per
// refusal reads closest to it.
@file:Suppress("ReturnCount")

package com.kevin.legion.carlink.protocol

import java.security.PublicKey

/**
 * The unit's pairing QR, section 3.1: `mnai-pair:1:<B64URL(unitPk)>:<B64URL(secret 16)>:<unit IPv4>:<port>`.
 *
 * Not a data class: the secret must never reach a generated toString. [parse] is the only way in and
 * refuses anything that is not exactly that shape, including a public key off the curve.
 */
class QrPayload private constructor(
    val unitPk: ByteArray,
    val unitKey: PublicKey,
    val secret: ByteArray,
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "QrPayload(redacted)"

    companion object {
        const val VERSION = 1
        private const val PREFIX = "mnai-pair"
        private const val PARTS = 6
        private const val SECRET_LEN = 16
        private const val MAX_PORT = 65535
        private val octet = Regex("""0|[1-9]\d{0,2}""")
        private val portDigits = Regex("""[1-9]\d{0,4}""")
        private const val KEY_PART = 2
        private const val SECRET_PART = 3
        private const val HOST_PART = 4
        private const val PORT_PART = 5
        private const val OCTETS = 4
        private const val MAX_OCTET = 255
        private const val NET_10 = 10
        private const val NET_172 = 172
        private const val NET_172_LOW = 16
        private const val NET_172_HIGH = 31
        private const val NET_192 = 192
        private const val NET_168 = 168

        fun parse(text: String): QrPayload? {
            val parts = text.split(':')
            if (parts.size != PARTS || parts[0] != PREFIX || parts[1] != VERSION.toString()) return null
            val unitPk = LinkCrypto.unb64url(parts[KEY_PART]) ?: return null
            val unitKey = P256.parsePublic(unitPk) ?: return null
            val secret = LinkCrypto.unb64url(parts[SECRET_PART])?.takeIf { it.size == SECRET_LEN } ?: return null
            val host = parts[HOST_PART].takeIf { isPrivateIpv4(it) } ?: return null
            val port = parts[PORT_PART].takeIf { it.matches(portDigits) }?.toInt()
                ?.takeIf { it <= MAX_PORT } ?: return null
            return QrPayload(unitPk, unitKey, secret, host, port)
        }

        /**
         * Canonical dotted decimal in 10/8, 172.16/12 or 192.168/16. The document only says the unit
         * listens on its Wi-Fi address (3.1); refusing a public address is this end's own choice, so a
         * forged QR can never point the phone at a host off the LAN.
         */
        fun isPrivateIpv4(text: String): Boolean {
            val o = text.split('.')
            if (o.size != OCTETS || o.any { !it.matches(octet) }) return false
            val n = o.map { it.toInt() }
            if (n.any { it > MAX_OCTET }) return false
            return n[0] == NET_10 || (n[0] == NET_172 && n[1] in NET_172_LOW..NET_172_HIGH) ||
                (n[0] == NET_192 && n[1] == NET_168)
        }
    }
}
