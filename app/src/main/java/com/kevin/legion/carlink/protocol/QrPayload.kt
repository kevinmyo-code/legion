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

        fun parse(text: String): QrPayload? {
            val parts = text.split(':')
            if (parts.size != PARTS || parts[0] != PREFIX || parts[1] != VERSION.toString()) return null
            val unitPk = LinkCrypto.unb64url(parts[2]) ?: return null
            val unitKey = P256.parsePublic(unitPk) ?: return null
            val secret = LinkCrypto.unb64url(parts[3])?.takeIf { it.size == SECRET_LEN } ?: return null
            val host = parts[4].takeIf { isPrivateIpv4(it) } ?: return null
            val port = parts[5].takeIf { it.matches(Regex("""[1-9]\d{0,4}""")) }?.toInt()
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
            if (o.size != 4 || o.any { !it.matches(octet) }) return false
            val n = o.map { it.toInt() }
            if (n.any { it > 255 }) return false
            return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168)
        }
    }
}
