package com.kevin.midnightai.location

/**
 * The location feeds of one paired unit (spec 3.7): one under the COMMITTED link key and, while a
 * new pairing waits for the phone, one under the PENDING key. HELLOs go out under both; the
 * phone's next authenticated datagram decides which key survives:
 *  - one that opens under PENDING commits it (the phone has the new key);
 *  - one that opens under COMMITTED while PENDING exists drops PENDING (the phone never got
 *    `done` and still has the old key).
 * Pure; the owner ([BeaconClient]) applies the decision through CarLink and rebuilds the set.
 */
class LinkFeedSet(val committed: PhoneFeed?, val pending: PhoneFeed?) {

    data class Result(val outcome: PhoneFeed.Outcome, val commitPending: Boolean = false, val dropPending: Boolean = false)

    fun hellos(intervalMs: Long): List<ByteArray> = listOfNotNull(pending, committed).map { it.hello(intervalMs) }

    fun receive(data: ByteArray, length: Int): Result {
        pending?.let { p ->
            if (p.opens(data, length)) {
                val out = p.receive(data, length)
                return Result(out, commitPending = out !is PhoneFeed.Outcome.Rejected && out !is PhoneFeed.Outcome.Revoke)
            }
        }
        val c = committed ?: return Result(PhoneFeed.Outcome.Rejected(PhoneFeed.Refusal.AUTH_FAILED))
        val out = c.receive(data, length)
        return Result(out, dropPending = pending != null && out !is PhoneFeed.Outcome.Rejected)
    }
}
