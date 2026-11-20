package com.kevin.midnightai.location

/**
 * The head unit's receiving end of the authenticated phone location feed (HU-1 T4), everything
 * but the socket: HELLO building, decryption, replay and ordering rules, fix-age handling. Pure
 * and clock-injected, so a fake phone in the tests drives every case (dropouts, out-of-order,
 * clock skew, a stale burst after reconnect, replay, tampering).
 *
 * The rules, in order, for each phone datagram:
 *  1. It must open under the link key (AES-GCM; anything else on the hotspot is dropped).
 *  2. It must echo a challenge this unit sent in one of its last [LIVE_CHALLENGES] HELLOs. A
 *     recorded datagram from an earlier drive therefore never passes, whatever its counter.
 *  3. Within one phone session its counter must be higher than the last accepted one (replay
 *     and out-of-order are both dropped: an older position is never better than a newer one).
 *  4. Its age: the phone's own "how old was this fix when sent", clamped to >= 0 so a fix is
 *     never stamped in the future, and dropped above [MAX_AGE_MS].
 *  5. Its fix time (unit receive time minus age) must be later than the last accepted fix's, so a
 *     burst of queued fixes after a reconnect can never move the position backwards.
 * No clock is compared across devices: the age is measured on the phone's clock and subtracted
 * from the unit's, so phone/unit clock skew does not matter.
 *
 * Not thread-safe by itself: [BeaconClient] owns one instance and calls it from its loops under
 * its own lock.
 */
class PhoneFeed(
    linkKey: ByteArray,
    private val nowNanos: () -> Long,
    private val random: (ByteArray) -> Unit,
) {
    private val p2uKey = PhoneLinkWire.phoneToUnitKey(linkKey)
    private val u2pKey = PhoneLinkWire.unitToPhoneKey(linkKey)
    private val helloSession = ByteArray(PhoneLinkWire.SESSION_BYTES).also(random)
    private var helloCounter = 0L
    private val challenges = ArrayDeque<ByteArray>()

    private var phoneSession: ByteArray? = null
    private var lastCounter = 0L
    private var lastFixNanos: Long? = null

    enum class Refusal {
        NOT_MNAI2, BAD_VERSION, BAD_LENGTH, AUTH_FAILED, WRONG_TYPE, BAD_FIELDS,
        UNKNOWN_CHALLENGE, REPLAY_OR_OUT_OF_ORDER, STALE, OLDER_THAN_SHOWN,
    }

    sealed interface Outcome {
        /** A fix to publish; [fixNanos] is its time on THIS unit's elapsedRealtimeNanos clock. */
        data class Fix(val fix: PhoneFix, val fixNanos: Long) : Outcome
        data class Zone(val zoneId: String) : Outcome
        data class Rejected(val why: Refusal) : Outcome
    }

    /** The next HELLO to send: asks for fixes every [intervalMs] and carries a fresh challenge. */
    fun hello(intervalMs: Long): ByteArray {
        val challenge = ByteArray(PhoneLinkWire.CHALLENGE_BYTES).also(random)
        challenges.addLast(challenge)
        while (challenges.size > LIVE_CHALLENGES) challenges.removeFirst()
        helloCounter++
        return PhoneLinkWire.encodeHello(u2pKey, helloSession, helloCounter, intervalMs, challenge)
    }

    fun receive(data: ByteArray, length: Int): Outcome {
        val (decoded, refusal) = PhoneLinkWire.decodePhone(p2uKey, data, length)
        if (decoded == null) return Outcome.Rejected(Refusal.valueOf(refusal!!))
        if (challenges.none { it.contentEquals(decoded.challenge) }) return Outcome.Rejected(Refusal.UNKNOWN_CHALLENGE)
        val sameSession = phoneSession?.contentEquals(decoded.session) == true
        if (sameSession && decoded.counter <= lastCounter) return Outcome.Rejected(Refusal.REPLAY_OR_OUT_OF_ORDER)
        return when (decoded) {
            is PhoneLinkWire.Decoded.Zone -> {
                adopt(decoded.session, decoded.counter)
                Outcome.Zone(decoded.zoneId)
            }
            is PhoneLinkWire.Decoded.Fix -> {
                val age = decoded.fix.ageMs.coerceAtLeast(0L)
                if (age > MAX_AGE_MS) return Outcome.Rejected(Refusal.STALE)
                val now = nowNanos()
                val fixNanos = now - age * 1_000_000L
                val last = lastFixNanos
                if (last != null && fixNanos <= last) return Outcome.Rejected(Refusal.OLDER_THAN_SHOWN)
                adopt(decoded.session, decoded.counter)
                lastFixNanos = fixNanos
                Outcome.Fix(decoded.fix.copy(ageMs = age), fixNanos)
            }
        }
    }

    private fun adopt(session: ByteArray, counter: Long) {
        phoneSession = session
        lastCounter = counter
    }

    companion object {
        /** HELLOs go out every 5 s; the current and previous challenge cover a late reply. */
        const val LIVE_CHALLENGES = 2

        /**
         * A fix older than this when it arrives is dropped. The screen calls a fix stale after
         * 3 s ([com.kevin.midnightai.ui.cruise.MiddlePanelText.isFresh]); this only stops older
         * ones from being published as the current position at all.
         */
        const val MAX_AGE_MS = 10_000L
    }
}
