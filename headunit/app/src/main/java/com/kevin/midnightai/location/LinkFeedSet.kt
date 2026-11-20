package com.kevin.midnightai.location

import com.kevin.midnightai.link.CarLink

/**
 * The location feeds of one paired unit (spec 3.7, revision 3): one under the COMMITTED link key
 * and, while a new pairing waits for the phone, one under the PENDING key. HELLOs go out under both.
 *  - A datagram that opens under PENDING commits it (the phone has the new key). Reported once per
 *    set (review N5): the owner commits, which rebuilds the set.
 *  - Datagrams under COMMITTED NEVER drop PENDING while a pairing session is open (review N1: the
 *    phone may still send under the old key until `done` opens). After the session, PENDING is
 *    dropped only after [QUIET_MS] of old-key traffic with no new-key contact, while new-key HELLOs
 *    kept going out the whole time.
 * Pure apart from the injected clock and session flag; the owner ([BeaconClient]) applies the
 * decision through CarLink.
 */
class LinkFeedSet(
    val committed: PhoneFeed?,
    val pending: PhoneFeed?,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sessionOpen: () -> Boolean = { CarLink.pairingSessionOpen },
) {
    data class Result(val outcome: PhoneFeed.Outcome, val commitPending: Boolean = false, val dropPending: Boolean = false)

    private var commitReported = false
    private var oldKeyOnlySince: Long? = null

    fun hellos(intervalMs: Long): List<ByteArray> = listOfNotNull(pending, committed).map { it.hello(intervalMs) }

    fun receive(data: ByteArray, length: Int): Result {
        pending?.let { p ->
            if (p.opens(data, length)) {
                val out = p.receive(data, length)
                val accepted = out !is PhoneFeed.Outcome.Rejected && out !is PhoneFeed.Outcome.Revoke
                if (accepted) oldKeyOnlySince = null
                val commit = accepted && !commitReported
                if (commit) commitReported = true
                return Result(out, commitPending = commit)
            }
        }
        val c = committed ?: return Result(PhoneFeed.Outcome.Rejected(PhoneFeed.Refusal.AUTH_FAILED))
        val out = c.receive(data, length)
        if (pending == null || out is PhoneFeed.Outcome.Rejected) return Result(out)
        if (sessionOpen()) {
            oldKeyOnlySince = null
            return Result(out)
        }
        val now = nowMs()
        val since = oldKeyOnlySince ?: now.also { oldKeyOnlySince = it }
        return Result(out, dropPending = now - since >= QUIET_MS)
    }

    companion object {
        /**
         * How long old-key traffic must continue, with no new-key contact, before PENDING is
         * dropped. A phone that opened `done` swaps keys in one Keystore write (well under a
         * second) and then answers the next new-key HELLO, which goes out every 1 s in nav mode and
         * at most every 5 s otherwise; 60 s is 12 to 60 unanswered new-key HELLOs, enough to ride
         * out a backgrounded phone or a hotspot blip without letting a phone that never got `done`
         * sit on a dead pending pairing.
         */
        const val QUIET_MS = 60_000L
    }
}
