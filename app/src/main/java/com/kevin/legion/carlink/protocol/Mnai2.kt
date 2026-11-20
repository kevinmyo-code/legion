// Guard clauses: the document says "refuse otherwise" at each check, and one early return per
// refusal reads closest to it.
@file:Suppress("ReturnCount")

package com.kevin.legion.carlink.protocol

import com.kevin.legion.carlink.protocol.LinkCrypto.ascii
import com.kevin.legion.carlink.protocol.LinkCrypto.hkdf
import java.nio.ByteBuffer

/** The two direction keys of section 4.1, derived from a link key. Zero with [wipe]. */
class DirectionKeys(linkKey: ByteArray) {
    val p2u: ByteArray = hkdf(linkKey, ByteArray(0), ascii("MNAI2 loc phone->unit v1"))
    val u2p: ByteArray = hkdf(linkKey, ByteArray(0), ascii("MNAI2 loc unit->phone v1"))

    override fun toString() = "DirectionKeys(redacted)"

    fun wipe() {
        p2u.fill(0)
        u2p.fill(0)
    }
}

/** An opened datagram's header and plaintext body. */
class Mnai2Datagram(val type: Char, val session: ByteArray, val counter: Long, val body: ByteArray)

/** A HELLO body (4.3): the requested fix interval and the challenge to echo. */
class Mnai2Hello(val intervalMs: Long, val challenge: ByteArray)

/**
 * One phone fix for a FIX body (4.3). Absent optional fields are null and their flag bit is clear.
 * [ageMs] is the phone's monotonic now minus the fix's monotonic time, never a wall clock.
 */
class PhoneFix(
    val lat: Double,
    val lng: Double,
    val ageMs: Long,
    val accuracyM: Float? = null,
    val bearingDeg: Float? = null,
    val speedMps: Float? = null,
    val altitudeM: Double? = null,
    val bearingAccDeg: Float? = null,
    val speedAccMps: Float? = null,
) {
    override fun toString() = "PhoneFix(redacted)"

    fun aged(age: Long) =
        PhoneFix(lat, lng, age, accuracyM, bearingDeg, speedMps, altitudeM, bearingAccDeg, speedAccMps)
}

/**
 * Section 4.2's datagram: `MNAI2` | version 1 | type | session 16 | counter u64 BE | SEAL(sessKey,
 * NONCE(dir, counter), aad = bytes 0..30, body). Big-endian throughout (4.3).
 */
object Mnai2 {
    const val PORT = 17311
    const val MAX_DATAGRAM = 256
    const val SESSION_LEN = 16
    const val CHALLENGE_LEN = 8
    const val HELLO = 'H'
    const val FIX = 'F'
    const val ZONE = 'Z'
    const val REVOKE = 'R'
    private const val MAGIC = "MNAI2"
    private const val VERSION: Byte = 1
    private const val OFF_TYPE = 6
    private const val OFF_SESSION = 7
    private const val OFF_COUNTER = 23
    const val HEADER_LEN = 31
    private const val TAG_LEN = 16
    private const val HELLO_LEN = 12
    const val FIX_LEN = 58
    private const val U32_MASK = 0xFFFFFFFFL
    private const val MIN_INTERVAL_MS = 250L
    private const val MAX_INTERVAL_MS = 60_000L
    private const val MAX_ZONE = 64
    private val zoneId = Regex("""[A-Za-z0-9_+\-/]{1,64}""")

    private const val FLAG_ACC = 1
    private const val FLAG_BEARING = 2
    private const val FLAG_SPEED = 4
    private const val FLAG_ALT = 8
    private const val FLAG_BEARING_ACC = 16
    private const val FLAG_SPEED_ACC = 32

    fun sessionKey(dirKey: ByteArray, session: ByteArray): ByteArray =
        hkdf(dirKey, session, ascii("MNAI2 session v1"))

    /** Seals a phone-to-unit datagram under [p2u]. */
    fun seal(p2u: ByteArray, type: Char, session: ByteArray, counter: Long, body: ByteArray): ByteArray {
        require(session.size == SESSION_LEN && counter >= 1)
        val header = ByteBuffer.allocate(HEADER_LEN).put(ascii(MAGIC)).put(VERSION).put(type.code.toByte())
            .put(session).putLong(counter).array()
        val key = sessionKey(p2u, session)
        try {
            val out = header + LinkCrypto.seal(key, LinkCrypto.nonce("P2U ", counter), header, body)
            check(out.size <= MAX_DATAGRAM)
            return out
        } finally {
            key.fill(0)
        }
    }

    /** Opens a unit-to-phone datagram under [u2p]; null when it is not MNAI2 or does not open. */
    fun openFromUnit(u2p: ByteArray, bytes: ByteArray, length: Int = bytes.size): Mnai2Datagram? =
        open(u2p, "U2P ", bytes, length)

    /** Opens a phone-to-unit datagram (tests: the unit's reading of what the phone sent). */
    fun openFromPhone(p2u: ByteArray, bytes: ByteArray, length: Int = bytes.size): Mnai2Datagram? =
        open(p2u, "P2U ", bytes, length)

    private fun open(dirKey: ByteArray, dir: String, bytes: ByteArray, length: Int): Mnai2Datagram? {
        if (length < HEADER_LEN + TAG_LEN || length > MAX_DATAGRAM) return null
        val header = bytes.copyOfRange(0, HEADER_LEN)
        val magicOk = header.copyOfRange(0, MAGIC.length).contentEquals(ascii(MAGIC))
        if (!magicOk || header[MAGIC.length] != VERSION) return null
        val session = header.copyOfRange(OFF_SESSION, OFF_COUNTER)
        val counter = ByteBuffer.wrap(header, OFF_COUNTER, Long.SIZE_BYTES).long
        if (counter < 1) return null // u64 >= 1; the top bit set reads negative here and is refused
        val key = sessionKey(dirKey, session)
        try {
            val sealed = bytes.copyOfRange(HEADER_LEN, length)
            val body = LinkCrypto.open(key, LinkCrypto.nonce(dir, counter), header, sealed)
                ?: return null
            return Mnai2Datagram(header[OFF_TYPE].toInt().toChar(), session, counter, body)
        } finally {
            key.fill(0)
        }
    }

    /** A HELLO body, or null when it is not 12 bytes or `intervalMs` is outside 250..60000. */
    fun readHello(body: ByteArray): Mnai2Hello? {
        if (body.size != HELLO_LEN) return null
        val b = ByteBuffer.wrap(body)
        val interval = b.int.toLong() and U32_MASK
        if (interval !in MIN_INTERVAL_MS..MAX_INTERVAL_MS) return null
        return Mnai2Hello(interval, ByteArray(CHALLENGE_LEN).also { b.get(it) })
    }

    fun fixBody(challenge: ByteArray, fix: PhoneFix): ByteArray {
        require(challenge.size == CHALLENGE_LEN)
        var flags = 0
        if (fix.accuracyM != null) flags = flags or FLAG_ACC
        if (fix.bearingDeg != null) flags = flags or FLAG_BEARING
        if (fix.speedMps != null) flags = flags or FLAG_SPEED
        if (fix.altitudeM != null) flags = flags or FLAG_ALT
        if (fix.bearingAccDeg != null) flags = flags or FLAG_BEARING_ACC
        if (fix.speedAccMps != null) flags = flags or FLAG_SPEED_ACC
        val age = fix.ageMs.coerceIn(0L, U32_MASK)
        return ByteBuffer.allocate(FIX_LEN).put(challenge).putShort(flags.toShort())
            .putDouble(fix.lat).putDouble(fix.lng)
            .putFloat(fix.accuracyM ?: 0f).putFloat(fix.bearingDeg ?: 0f).putFloat(fix.speedMps ?: 0f)
            .putDouble(fix.altitudeM ?: 0.0).putInt(age.toInt())
            .putFloat(fix.bearingAccDeg ?: 0f).putFloat(fix.speedAccMps ?: 0f)
            .array()
    }

    fun zoneBody(challenge: ByteArray, zone: String): ByteArray {
        require(challenge.size == CHALLENGE_LEN && zone.length <= MAX_ZONE && zoneId.matches(zone))
        return challenge + ascii(zone)
    }

    fun revokeBody(challenge: ByteArray): ByteArray {
        require(challenge.size == CHALLENGE_LEN)
        return challenge.copyOf()
    }
}
