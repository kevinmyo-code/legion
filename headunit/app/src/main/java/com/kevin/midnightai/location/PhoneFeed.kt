package com.kevin.midnightai.location

/**
 * The head unit's receiving end of the authenticated phone location feed under ONE link key
 * (HU-1 T4; spec section 4 of `.legion/reports/headunit-link-protocol-1120.md`, revision 2).
 * Everything but the socket: HELLO building, decryption, replay and ordering rules, fix age. Pure
 * and clock-injected; tested against the independent vectors and a fake phone.
 *
 * Rules for each phone datagram, in order (spec 4.4):
 *  1. It must open under the link key.
 *  2. It must echo a challenge this unit sent within the last [MAX_AGE_MS].
 *  3. Same phone session: counter strictly above the last accepted. A different session: accepted
 *     only if its challenge was sent strictly after the challenge echoed by the last accepted
 *     datagram, so recordings of an older session can never interleave with a newer one.
 *  4. Age is conservative: the phone's own age PLUS the time since this unit sent the echoed
 *     challenge, which counts time in flight and any delay an attacker adds. Above [MAX_AGE_MS]
 *     it is dropped. The age travels as u32 and is read unsigned.
 *  5. The fix's time must be later than the last accepted fix's, so a burst of queued fixes after
 *     a reconnect never moves the position backwards.
 * No clock is compared across devices.
 *
 * Not thread-safe by itself: the owner calls it under one lock.
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
    private val cache = PhoneLinkWire.SessionKeyCache()

    private class Sent(val challenge: ByteArray, val atNanos: Long)
    private val challenges = ArrayDeque<Sent>()

    private var phoneSession: ByteArray? = null
    private var lastCounter = 0L
    private var lastEchoSentAt: Long? = null
    private var lastFixNanos: Long? = null

    enum class Refusal {
        NOT_MNAI2, BAD_VERSION, BAD_LENGTH, AUTH_FAILED, WRONG_TYPE, BAD_FIELDS,
        UNKNOWN_CHALLENGE, REPLAY_OR_OUT_OF_ORDER, STALE, OLDER_THAN_SHOWN,
    }

    sealed interface Outcome {
        /** A fix to publish; [fixNanos] is its time on THIS unit's elapsedRealtimeNanos clock. */
        data class Fix(val fix: PhoneFix, val fixNanos: Long) : Outcome
        data class Zone(val zoneId: String) : Outcome
        /** The phone's "Forget head unit" (spec 4.3): forget this pairing. */
        data object Revoke : Outcome

        /**
         * Rev 9: an engine sign-in for this unit, handed over after the pairing. Not a data class:
         * it holds a token.
         */
        class Credential(val token: String, val vehicleId: String?) : Outcome {
            override fun toString() = "Credential(redacted)"
        }
        data class Rejected(val why: Refusal) : Outcome
    }

    /** The next HELLO: asks for fixes every [intervalMs] and carries a fresh challenge. */
    fun hello(intervalMs: Long): ByteArray {
        val now = nowNanos()
        prune(now)
        val challenge = ByteArray(PhoneLinkWire.CHALLENGE_BYTES).also(random)
        challenges.addLast(Sent(challenge, now))
        while (challenges.size > MAX_LIVE_CHALLENGES) challenges.removeFirst()
        helloCounter++
        return PhoneLinkWire.encodeHello(u2pKey, helloSession, helloCounter, intervalMs, challenge)
    }

    /**
     * This unit's "I stored it" (rev 9), on the same session and counter ladder as its HELLOs: one
     * counter per session, so no GCM nonce is ever used twice under that key.
     */
    fun tokenOk(fingerprint: ByteArray): ByteArray {
        helloCounter++
        return PhoneLinkWire.encodeTokenOk(u2pKey, helloSession, helloCounter, fingerprint)
    }

    /** True when [data] opens under this feed's key (no other check, no state change). */
    fun opens(data: ByteArray, length: Int): Boolean = PhoneLinkWire.decodePhone(p2uKey, data, length, cache).first != null

    fun receive(data: ByteArray, length: Int): Outcome {
        val (decoded, refusal) = PhoneLinkWire.decodePhone(p2uKey, data, length, cache)
        if (decoded == null) return Outcome.Rejected(Refusal.valueOf(refusal!!))
        val now = nowNanos()
        prune(now)
        val sent = challenges.lastOrNull { it.challenge.contentEquals(decoded.challenge) }
            ?: return Outcome.Rejected(Refusal.UNKNOWN_CHALLENGE)
        val sameSession = phoneSession?.contentEquals(decoded.session) == true
        if (sameSession) {
            if (decoded.counter <= lastCounter) return Outcome.Rejected(Refusal.REPLAY_OR_OUT_OF_ORDER)
        } else {
            val last = lastEchoSentAt
            if (last != null && sent.atNanos <= last) return Outcome.Rejected(Refusal.REPLAY_OR_OUT_OF_ORDER)
        }
        return when (decoded) {
            is PhoneLinkWire.Decoded.Zone -> {
                adopt(decoded, sent)
                Outcome.Zone(decoded.zoneId)
            }
            is PhoneLinkWire.Decoded.Revoke -> {
                adopt(decoded, sent)
                Outcome.Revoke
            }
            is PhoneLinkWire.Decoded.Credential -> {
                adopt(decoded, sent)
                Outcome.Credential(decoded.token, decoded.vehicleId)
            }
            is PhoneLinkWire.Decoded.Fix -> {
                val inFlightMs = (now - sent.atNanos).coerceAtLeast(0L) / 1_000_000L
                val age = decoded.fix.ageMs + inFlightMs
                if (decoded.fix.ageMs > MAX_AGE_MS || age > MAX_AGE_MS) return Outcome.Rejected(Refusal.STALE)
                val fixNanos = now - age * 1_000_000L
                val lastFix = lastFixNanos
                if (lastFix != null && fixNanos <= lastFix) return Outcome.Rejected(Refusal.OLDER_THAN_SHOWN)
                adopt(decoded, sent)
                lastFixNanos = fixNanos
                Outcome.Fix(decoded.fix.copy(ageMs = age), fixNanos)
            }
        }
    }

    private fun adopt(decoded: PhoneLinkWire.Decoded, sent: Sent) {
        phoneSession = decoded.session
        lastCounter = decoded.counter
        lastEchoSentAt = maxOf(lastEchoSentAt ?: Long.MIN_VALUE, sent.atNanos)
    }

    private fun prune(now: Long) {
        while (challenges.isNotEmpty() && now - challenges.first().atNanos > MAX_AGE_MS * 1_000_000L) {
            challenges.removeFirst()
        }
    }

    companion object {
        /** A fix older than this (conservatively) is never published; the screen calls 3 s stale. */
        const val MAX_AGE_MS = 10_000L

        /** HELLOs go out at the fix rate (1 s in nav mode), so 10 s holds at most ~10 of them. */
        const val MAX_LIVE_CHALLENGES = 16

        /** How often the unit sends a HELLO for a requested fix interval (spec 4.3): never slower than 5 s. */
        fun helloIntervalMs(requestedFixIntervalMs: Long): Long = requestedFixIntervalMs.coerceIn(250L, 5_000L)
    }
}
