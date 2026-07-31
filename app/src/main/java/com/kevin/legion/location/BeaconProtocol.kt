package com.kevin.legion.location

/**
 * A single position report carried over the GPS beacon link.
 *
 * Deliberately plain Kotlin - no `android.location.Location` - so the whole wire
 * format is unit-testable on the JVM, same shape as
 * [com.kevin.legion.vehicle.ShellyCloudOpener]'s pure URL/body builders.
 *
 * **There is no timestamp field, and that is on purpose.** The two devices'
 * `elapsedRealtime` bases are unrelated (different boot times) and their wall
 * clocks only agree to whatever NTP happens to have done, so any timestamp the
 * phone puts on the wire is meaningless to the head unit. Freshness instead comes
 * from the transport being pull-gated: the phone only transmits while HELLOs keep
 * arriving, and it only ever sends its *current* fix, so a packet that arrives is
 * by construction a live one. The receiver stamps its own clocks - see
 * [LocationController.acceptExternal].
 */
data class BeaconFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float?,
    val bearingDeg: Float?,
    val speedMps: Float?,
    val altitudeM: Double?,
)

/**
 * Wire format for the phone-as-GPS-beacon link (CLAUDE.md §14: the Cherokee's head
 * unit cannot use its own GPS - connecting the antenna browns out the shared
 * WiFi/BT rail - so a phone on the same hotspot supplies the fix instead).
 *
 * Two datagram types, both ASCII, both one packet with no framing or continuation:
 *
 * ```
 * MNAI1|H|<intervalMs>                                   head unit -> phone
 * MNAI1|F|<lat>|<lng>|<acc>|<bearing>|<speed>|<alt>      phone -> head unit
 * ```
 *
 * UDP is the right transport here precisely because loss does not matter: the next
 * fix is at most a second away, so there is nothing to retransmit and no socket
 * state machine, reconnect logic, or head-of-line blocking to get wrong.
 *
 * Optional fields ride as empty strings. Number formatting uses Kotlin/Java's
 * locale-independent `toString`/`toDoubleOrNull`, so a device in a comma-decimal
 * locale cannot corrupt the stream.
 *
 * Both decoders validate hard and return null on anything they do not fully
 * understand. Anyone else on the hotspot can send this port a datagram, so a
 * malformed or hostile packet must be dropped rather than allowed to poison
 * [LocationController]'s state.
 */
object BeaconProtocol {
    /** Arbitrary high port, fixed on both ends so no discovery handshake is needed. */
    const val PORT = 17311

    /** Bounded read buffer - every valid packet is far smaller than this. */
    const val MAX_PACKET_BYTES = 256

    /** Version-stamped so a future format change can be told apart, not misparsed. */
    private const val MAGIC = "MNAI1"
    private const val TYPE_HELLO = "H"
    private const val TYPE_FIX = "F"
    private const val SEP = '|'

    /** Clamp on the interval a peer may request, so a bad packet can't peg or stall the sender. */
    private const val MIN_INTERVAL_MS = 250L
    private const val MAX_INTERVAL_MS = 60_000L

    fun encodeHello(intervalMs: Long): ByteArray =
        "$MAGIC$SEP$TYPE_HELLO$SEP${intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)}"
            .toByteArray(Charsets.US_ASCII)

    /** Returns the requested send interval in ms, or null if this is not a valid HELLO. */
    fun decodeHello(data: ByteArray, length: Int): Long? {
        val parts = split(data, length) ?: return null
        if (parts.size != 3 || parts[1] != TYPE_HELLO) return null
        val interval = parts[2].toLongOrNull() ?: return null
        return interval.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    fun encodeFix(fix: BeaconFix): ByteArray = buildString {
        append(MAGIC).append(SEP).append(TYPE_FIX).append(SEP)
        append(fix.lat).append(SEP)
        append(fix.lng).append(SEP)
        append(fix.accuracyM?.toString().orEmpty()).append(SEP)
        append(fix.bearingDeg?.toString().orEmpty()).append(SEP)
        append(fix.speedMps?.toString().orEmpty()).append(SEP)
        append(fix.altitudeM?.toString().orEmpty())
    }.toByteArray(Charsets.US_ASCII)

    /** Returns the reported position, or null if this is not a valid, in-range FIX. */
    fun decodeFix(data: ByteArray, length: Int): BeaconFix? {
        val parts = split(data, length) ?: return null
        if (parts.size != 8 || parts[1] != TYPE_FIX) return null
        val lat = parts[2].toDoubleOrNull() ?: return null
        val lng = parts[3].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lng.isFinite()) return null
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return null
        return BeaconFix(
            lat = lat,
            lng = lng,
            accuracyM = parts[4].optFloat(),
            bearingDeg = parts[5].optFloat(),
            speedMps = parts[6].optFloat(),
            altitudeM = parts[7].optDouble(),
        )
    }

    private fun split(data: ByteArray, length: Int): List<String>? {
        if (length <= 0 || length > MAX_PACKET_BYTES || length > data.size) return null
        val text = String(data, 0, length, Charsets.US_ASCII)
        val parts = text.split(SEP)
        if (parts.isEmpty() || parts[0] != MAGIC) return null
        return parts
    }

    /** Blank means "the sender had no value for this"; garbage also degrades to absent. */
    private fun String.optFloat(): Float? = takeIf { it.isNotEmpty() }?.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun String.optDouble(): Double? = takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
}
