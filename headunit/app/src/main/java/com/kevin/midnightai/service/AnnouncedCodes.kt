package com.kevin.midnightai.service

/**
 * Which trouble codes the driver has actually been TOLD about, as distinct from which ones the ECU
 * has been SEEN reporting.
 *
 * The health monitor conflated the two: it kept one `knownCodes` set and advanced it the moment it
 * ASKED for a line to be spoken, so a code whose line was then gated (mute, a phone call) or
 * dropped (held mid-conversation and lost on a goodbye) was never announced and never would be -
 * the next scan saw it in the baseline and said nothing. A dropped alert was lost, not deferred.
 *
 * This is the phone's rule in the unit's shape. Alfred's raise sites advance their own "already
 * told" marker only on a delivered outcome and retry otherwise (`DongleCarController`'s
 * `markAsked` on `Raised`, `WakeBrief`'s `clearLastDay` on `NotNow`). Here the marker advances from
 * [ProactiveBus.SpeakRequest.onSpoken], which fires only once a line has really reached the socket,
 * so every way of not saying it - gated, held, evicted, stale, a socket that never connected -
 * leaves the code untold and the next scan raises it again.
 *
 * It fails toward a REPEAT rather than a silence, and that is the deliberate direction: the window
 * between asking for a line and its reaching the socket is seconds, the scan interval is five
 * minutes, so a duplicate needs the first line to still be waiting five minutes later. Hearing
 * about an overheat twice costs nothing; not hearing about it costs the engine.
 *
 * Thread-safe: the scan loop reads it on the service scope and [markTold] is called back from
 * whichever thread fed the socket.
 */
internal class AnnouncedCodes {
    private val told = mutableSetOf<String>()

    /** The codes in [codes] the driver has not been told about yet. */
    @Synchronized
    fun untold(codes: Set<String>): Set<String> = codes.filterNotTo(mutableSetOf()) { it in told }

    /** Records that [codes] were said out loud (or, at the first reading, are the baseline). */
    @Synchronized
    fun markTold(codes: Set<String>) {
        told += codes
    }

    /**
     * Forgets every code the ECU no longer reports, so one that clears and comes back is news
     * again. The old single-set version got this for free; keeping two sets would quietly lose it.
     */
    @Synchronized
    fun keepOnly(codes: Set<String>) {
        told.retainAll(codes)
    }
}
