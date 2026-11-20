package com.kevin.midnightai.location

import com.kevin.midnightai.link.LinkCrypto
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One phone-fed position (HU-1 T4). Like [BeaconFix], plus what the authenticated feed adds:
 * [ageMs], how old the fix already was on the phone when it was sent (its own monotonic clock,
 * so no clock is ever compared across devices), and the bearing / speed accuracies when the
 * phone has them.
 */
data class PhoneFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val bearingDeg: Float?,
    val speedMps: Float?,
    val altitudeM: Double?,
    val ageMs: Long,
    val bearingAccuracyDeg: Float? = null,
    val speedAccuracyMps: Float? = null,
)

/**
 * MNAI2: the authenticated location link over the hotspot (HU-1 T4). Byte-exact spec, shared with
 * the phone end, in `.legion/reports/headunit-link-protocol-1120.md` section 4. Pure, no Android.
 *
 * Datagram = header (31 bytes, sent in clear, authenticated as GCM associated data) + sealed body:
 * ```
 * 0  "MNAI2"      5 bytes ASCII
 * 5  version      1 byte  = 1
 * 6  type         1 byte  'H' hello (unit->phone), 'F' fix, 'Z' zone (phone->unit)
 * 7  session      16 bytes, random per sender start
 * 23 counter      8 bytes big-endian, starts at 1, +1 per datagram of that session
 * 31 sealed body  AES-256-GCM(ciphertext || 16-byte tag)
 * ```
 * Keys: `dirKey = HKDF(linkKey, "", "MNAI2 loc phone->unit v1" | "MNAI2 loc unit->phone v1")`,
 * `sessionKey = HKDF(dirKey, salt = session, "MNAI2 session v1")`; nonce = direction label (4
 * ASCII bytes "P2U " / "U2P ") + counter. A fresh session key per sender start means a counter
 * that restarts at 1 never reuses a nonce under the same key.
 */
object PhoneLinkWire {
    const val PORT = BeaconProtocol.PORT
    const val VERSION: Byte = 1
    const val TYPE_HELLO: Byte = 'H'.code.toByte()
    const val TYPE_FIX: Byte = 'F'.code.toByte()
    const val TYPE_ZONE: Byte = 'Z'.code.toByte()
    const val SESSION_BYTES = 16
    const val CHALLENGE_BYTES = 8
    const val HEADER_BYTES = 5 + 1 + 1 + SESSION_BYTES + 8
    const val MAX_PACKET_BYTES = 256
    private val MAGIC = "MNAI2".toByteArray(Charsets.US_ASCII)
    private val P2U = "P2U ".toByteArray(Charsets.US_ASCII)
    private val U2P = "U2P ".toByteArray(Charsets.US_ASCII)
    private const val FIX_BODY = CHALLENGE_BYTES + 2 + 8 + 8 + 4 + 4 + 4 + 8 + 4 + 4 + 4
    private const val HELLO_BODY = 4 + CHALLENGE_BYTES
    private const val MAX_ZONE_BYTES = 64

    fun phoneToUnitKey(linkKey: ByteArray): ByteArray =
        LinkCrypto.hkdf(linkKey, ByteArray(0), "MNAI2 loc phone->unit v1".toByteArray(Charsets.US_ASCII))

    fun unitToPhoneKey(linkKey: ByteArray): ByteArray =
        LinkCrypto.hkdf(linkKey, ByteArray(0), "MNAI2 loc unit->phone v1".toByteArray(Charsets.US_ASCII))

    fun sessionKey(directionKey: ByteArray, session: ByteArray): ByteArray =
        LinkCrypto.hkdf(directionKey, session, "MNAI2 session v1".toByteArray(Charsets.US_ASCII))

    /** A parsed, authenticated datagram: [type], [session], [counter] and the opened [body]. */
    class Opened(val type: Byte, val session: ByteArray, val counter: Long, val body: ByteArray)

    enum class Refusal { NOT_MNAI2, BAD_VERSION, BAD_LENGTH, AUTH_FAILED }

    private fun header(type: Byte, session: ByteArray, counter: Long): ByteArray {
        require(session.size == SESSION_BYTES && counter > 0)
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC).put(VERSION).put(type).put(session).putLong(counter).array()
    }

    private fun seal(dirKey: ByteArray, direction: ByteArray, type: Byte, session: ByteArray, counter: Long, body: ByteArray): ByteArray {
        val h = header(type, session, counter)
        val key = sessionKey(dirKey, session)
        val sealed = LinkCrypto.seal(key, LinkCrypto.nonce(direction, counter), h, body)
        key.fill(0)
        return h + sealed
    }

    /** Opens a datagram sealed under [dirKey] in [direction]; the refusal says why not. */
    private fun open(dirKey: ByteArray, direction: ByteArray, data: ByteArray, length: Int): Pair<Opened?, Refusal?> {
        if (length < HEADER_BYTES + LinkCrypto.TAG_BYTES || length > MAX_PACKET_BYTES || length > data.size) {
            return null to (if (length >= 5 && magicOk(data)) Refusal.BAD_LENGTH else Refusal.NOT_MNAI2)
        }
        if (!magicOk(data)) return null to Refusal.NOT_MNAI2
        if (data[5] != VERSION) return null to Refusal.BAD_VERSION
        val buf = ByteBuffer.wrap(data, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        buf.position(6)
        val type = buf.get()
        val session = ByteArray(SESSION_BYTES).also { buf.get(it) }
        val counter = buf.getLong()
        if (counter <= 0) return null to Refusal.AUTH_FAILED
        val key = sessionKey(dirKey, session)
        val body = LinkCrypto.open(
            key, LinkCrypto.nonce(direction, counter), data.copyOfRange(0, HEADER_BYTES),
            data.copyOfRange(HEADER_BYTES, length),
        )
        key.fill(0)
        return if (body == null) null to Refusal.AUTH_FAILED else Opened(type, session, counter, body) to null
    }

    private fun magicOk(data: ByteArray): Boolean = data.size >= 5 && (0 until 5).all { data[it] == MAGIC[it] }

    // --- Unit -> phone ------------------------------------------------------------------------

    fun encodeHello(unitToPhoneKey: ByteArray, session: ByteArray, counter: Long, intervalMs: Long, challenge: ByteArray): ByteArray {
        require(challenge.size == CHALLENGE_BYTES)
        val body = ByteBuffer.allocate(HELLO_BODY).order(ByteOrder.BIG_ENDIAN)
            .putInt(intervalMs.coerceIn(250L, 60_000L).toInt()).put(challenge).array()
        return seal(unitToPhoneKey, U2P, TYPE_HELLO, session, counter, body)
    }

    /** Phone end (and the tests' fake phone): the requested interval and the challenge to echo. */
    data class Hello(val intervalMs: Long, val challenge: ByteArray, val session: ByteArray, val counter: Long)

    fun decodeHello(unitToPhoneKey: ByteArray, data: ByteArray, length: Int): Hello? {
        val opened = open(unitToPhoneKey, U2P, data, length).first ?: return null
        if (opened.type != TYPE_HELLO || opened.body.size != HELLO_BODY) return null
        val b = ByteBuffer.wrap(opened.body).order(ByteOrder.BIG_ENDIAN)
        val interval = b.getInt().toLong().coerceIn(250L, 60_000L)
        val challenge = ByteArray(CHALLENGE_BYTES).also { b.get(it) }
        return Hello(interval, challenge, opened.session, opened.counter)
    }

    // --- Phone -> unit ------------------------------------------------------------------------

    private const val F_ACC = 1
    private const val F_BEARING = 2
    private const val F_SPEED = 4
    private const val F_ALT = 8
    private const val F_BEARING_ACC = 16
    private const val F_SPEED_ACC = 32

    /** Phone end (reference for the phone app, and the tests' fake phone). */
    fun encodeFix(phoneToUnitKey: ByteArray, session: ByteArray, counter: Long, challenge: ByteArray, fix: PhoneFix): ByteArray {
        require(challenge.size == CHALLENGE_BYTES)
        var flags = 0
        if (fix.accuracyM != null) flags = flags or F_ACC
        if (fix.bearingDeg != null) flags = flags or F_BEARING
        if (fix.speedMps != null) flags = flags or F_SPEED
        if (fix.altitudeM != null) flags = flags or F_ALT
        if (fix.bearingAccuracyDeg != null) flags = flags or F_BEARING_ACC
        if (fix.speedAccuracyMps != null) flags = flags or F_SPEED_ACC
        val body = ByteBuffer.allocate(FIX_BODY).order(ByteOrder.BIG_ENDIAN)
            .put(challenge)
            .putShort(flags.toShort())
            .putDouble(fix.lat).putDouble(fix.lng)
            .putFloat(fix.accuracyM ?: 0f).putFloat(fix.bearingDeg ?: 0f).putFloat(fix.speedMps ?: 0f)
            .putDouble(fix.altitudeM ?: 0.0)
            .putInt(fix.ageMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            .putFloat(fix.bearingAccuracyDeg ?: 0f).putFloat(fix.speedAccuracyMps ?: 0f)
            .array()
        return seal(phoneToUnitKey, P2U, TYPE_FIX, session, counter, body)
    }

    fun encodeZone(phoneToUnitKey: ByteArray, session: ByteArray, counter: Long, challenge: ByteArray, zoneId: String): ByteArray {
        val id = zoneId.toByteArray(Charsets.US_ASCII)
        require(challenge.size == CHALLENGE_BYTES && id.size in 1..MAX_ZONE_BYTES)
        return seal(phoneToUnitKey, P2U, TYPE_ZONE, session, counter, challenge + id)
    }

    /** What the unit got out of one phone datagram, before ordering and freshness rules. */
    sealed interface Decoded {
        val session: ByteArray
        val counter: Long
        val challenge: ByteArray

        class Fix(override val session: ByteArray, override val counter: Long, override val challenge: ByteArray, val fix: PhoneFix) : Decoded
        class Zone(override val session: ByteArray, override val counter: Long, override val challenge: ByteArray, val zoneId: String) : Decoded
    }

    enum class BodyRefusal { WRONG_TYPE, BAD_FIELDS }

    /** Unit end: opens and parses one phone datagram. Left: refusal; right: decoded. */
    fun decodePhone(phoneToUnitKey: ByteArray, data: ByteArray, length: Int): Pair<Decoded?, String?> {
        val (opened, refusal) = open(phoneToUnitKey, P2U, data, length)
        if (opened == null) return null to refusal!!.name
        val body = opened.body
        return when (opened.type) {
            TYPE_FIX -> parseFix(opened, body)
            TYPE_ZONE -> {
                if (body.size <= CHALLENGE_BYTES || body.size > CHALLENGE_BYTES + MAX_ZONE_BYTES) return null to BodyRefusal.BAD_FIELDS.name
                val id = String(body, CHALLENGE_BYTES, body.size - CHALLENGE_BYTES, Charsets.US_ASCII)
                if (!Regex("[A-Za-z0-9_+\\-/]+").matches(id)) return null to BodyRefusal.BAD_FIELDS.name
                Decoded.Zone(opened.session, opened.counter, body.copyOfRange(0, CHALLENGE_BYTES), id) to null
            }
            else -> null to BodyRefusal.WRONG_TYPE.name
        }
    }

    private fun parseFix(opened: Opened, body: ByteArray): Pair<Decoded?, String?> {
        if (body.size != FIX_BODY) return null to BodyRefusal.BAD_FIELDS.name
        val b = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val challenge = ByteArray(CHALLENGE_BYTES).also { b.get(it) }
        val flags = b.getShort().toInt()
        val lat = b.getDouble()
        val lng = b.getDouble()
        val acc = b.getFloat()
        val bearing = b.getFloat()
        val speed = b.getFloat()
        val alt = b.getDouble()
        val age = b.getInt().toLong()
        val bearingAcc = b.getFloat()
        val speedAcc = b.getFloat()
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            return null to BodyRefusal.BAD_FIELDS.name
        }
        fun f(bit: Int, v: Float): Float? = v.takeIf { flags and bit != 0 && it.isFinite() }
        val fix = PhoneFix(
            lat = lat,
            lng = lng,
            accuracyM = f(F_ACC, acc)?.takeIf { it >= 0f },
            bearingDeg = f(F_BEARING, bearing)?.takeIf { it in 0f..360f },
            speedMps = f(F_SPEED, speed)?.takeIf { it >= 0f },
            altitudeM = alt.takeIf { flags and F_ALT != 0 && it.isFinite() },
            ageMs = age,
            bearingAccuracyDeg = f(F_BEARING_ACC, bearingAcc)?.takeIf { it >= 0f },
            speedAccuracyMps = f(F_SPEED_ACC, speedAcc)?.takeIf { it >= 0f },
        )
        return Decoded.Fix(opened.session, opened.counter, challenge, fix) to null
    }
}
