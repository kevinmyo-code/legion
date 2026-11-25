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
 * The marker advances from [ProactiveBus.SpeakRequest.onSpoken], which fires only once a line has
 * really reached the socket, so every way of not saying it - gated, held, evicted, stale, a socket
 * that never connected - leaves the code untold and the next scan raises it again.
 *
 * **Where the phone sits on this, stated exactly, because the obvious claim is wrong.** Alfred's
 * raise sites generally advance their marker only on a delivered outcome and retry otherwise
 * (`DongleCarController`'s `markAsked` on `Raised`, `WakeBrief`'s `clearLastDay` on `NotNow`, the
 * pollers' `NotNow -> RETRY`). **His own health monitor does NOT**: `NewCodeTracker.observe`
 * records the code inside the call and `overheatAnnounced = true` runs whatever the raise returned.
 * It can afford that, and this cannot: on the phone a line that may not be spoken is POSTED
 * instead (`ProactiveDelivery.route`, "gated does not mean lost"), and the unit has no shade route
 * for a proactive line at all - nothing posts, so an unspoken line is simply unspoken. So this is
 * the phone's general rule applied where his specific monitor relies on a fallback the unit does
 * not have, which is also Kevin's ruling of 2026-11-24 read literally.
 *
 * It fails toward a REPEAT rather than a silence, and that is the deliberate direction: the window
 * between asking for a line and its reaching the socket is seconds, the scan interval is five
 * minutes, so a duplicate needs the first line to still be waiting five minutes later. Hearing
 * about an overheat twice costs nothing; not hearing about it costs the engine.
 *
 * Thread-safe: the scan loop calls [onScan] on the service scope and [markTold] comes back from
 * whichever thread fed the socket.
 */
internal class AnnouncedCodes {
    private val told = mutableSetOf<String>()

    /**
     * One scan of the OBD port: what this scan should announce, and nothing else.
     *
     * [firstReading] is the first scan of the process, whose codes are the baseline: the opener
     * surfaces them, so they count as told and are never announced here. Pruning happens first, so
     * a code that cleared and came back is news again.
     *
     * The whole decision lives here rather than in the scan loop, so it is the tested thing. The
     * loop is left with the `code_events` baseline, which is a different question.
     */
    @Synchronized
    fun onScan(codes: Set<String>, firstReading: Boolean): Set<String> {
        told.retainAll(codes)
        if (firstReading) {
            told += codes
            return emptySet()
        }
        return codes.filterNotTo(mutableSetOf()) { it in told }
    }

    /** Records that [codes] were said out loud. Called from a line's `onSpoken`, never before. */
    @Synchronized
    fun markTold(codes: Set<String>) {
        told += codes
    }
}
