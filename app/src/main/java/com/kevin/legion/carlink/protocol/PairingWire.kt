package com.kevin.legion.carlink.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream

/**
 * Section 3.2 framing: a 4-byte big-endian length N, then N bytes of UTF-8 JSON, N in 1..8192, the
 * length checked before any allocation. [FrameReader] reassembles a header split across reads.
 */
object Frames {
    const val MAX = 8192
    private const val HEADER = 4
    private const val BYTE_MASK = 0xFF
    private const val B1 = 24
    private const val B2 = 16
    private const val B3 = 8

    fun encode(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        require(body.size in 1..MAX) { "frame size" }
        val n = body.size
        return byteArrayOf((n ushr B1).toByte(), (n ushr B2).toByte(), (n ushr B3).toByte(), n.toByte()) + body
    }

    /** Incremental reader. [feed] returns whole frames; a length outside 1..8192 throws [BadFrame]. */
    class FrameReader {
        private val buf = ByteArrayOutputStream()

        fun feed(bytes: ByteArray, count: Int = bytes.size): List<String> {
            buf.write(bytes, 0, count)
            val out = mutableListOf<String>()
            var data = buf.toByteArray()
            var pos = 0
            while (data.size - pos >= HEADER) {
                var n = 0
                for (i in 0 until HEADER) n = (n shl B3) or (data[pos + i].toInt() and BYTE_MASK)
                if (n < 1 || n > MAX) throw BadFrame()
                if (data.size - pos - HEADER < n) break
                out += String(data, pos + HEADER, n, Charsets.UTF_8)
                pos += HEADER + n
            }
            buf.reset()
            buf.write(data, pos, data.size - pos)
            data.fill(0)
            return out
        }
    }

    class BadFrame : Exception("frame length outside 1..8192")
}

/** One message from the unit, section 3.4, parsed and nothing more. */
sealed interface UnitMessage {
    class Ack(val v: Int?, val kc: ByteArray?) : UnitMessage
    class Sealed(val type: String, val c: ByteArray?) : UnitMessage
    class Abort(val why: String?) : UnitMessage
    object Unreadable : UnitMessage
}

/** Builds and reads section 3.4's JSON. Compact, fields in the document's order. */
object PairingWire {
    const val CODE_OK = "code_ok"
    const val DONE = "done"
    private val json = Json { isLenient = false }

    private fun q(s: String) = JsonPrimitive(s).toString()

    fun hello(phonePk: ByteArray, mac: ByteArray): String =
        """{"t":"hello","v":1,"pk":${q(LinkCrypto.b64(phonePk))},"mac":${q(LinkCrypto.b64(mac))}}"""

    fun sealedMessage(type: String, c: ByteArray): String = """{"t":${q(type)},"c":${q(LinkCrypto.b64(c))}}"""

    fun abort(why: String): String = """{"t":"abort","why":${q(why)}}"""

    fun read(text: String): UnitMessage {
        val obj: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: IllegalArgumentException) {
            return UnitMessage.Unreadable
        }
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return when (str("t")) {
            "ack" -> UnitMessage.Ack(
                (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull,
                str("kc")?.let(LinkCrypto::unb64),
            )
            CODE_OK, DONE -> UnitMessage.Sealed(str("t")!!, str("c")?.let(LinkCrypto::unb64))
            "abort" -> UnitMessage.Abort(str("why"))
            else -> UnitMessage.Unreadable
        }
    }
}
