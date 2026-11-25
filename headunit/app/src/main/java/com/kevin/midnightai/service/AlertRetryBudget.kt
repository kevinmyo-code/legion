package com.kevin.midnightai.service

/**
 * How many times the health monitor may re-ask for an urgent line that was never actually spoken,
 * and when it is allowed to ask at all.
 *
 * [AnnouncedCodes] made an unspoken alert retry rather than vanish: the marker only advances from
 * `onSpoken`, so a code whose line was gated, held, evicted or sat on a socket that never connected
 * is raised again on the next scan. That is the right direction and it has no floor. A unit parked
 * out of signal, or one whose Gemini key was never entered, would ask every five minutes for as
 * long as the code is stored, and each ask with nothing live spins up a cold Live connect that
 * cannot succeed. Loud, billable where it half-connects, and no closer to telling the driver.
 *
 * Two bounds, which together are "never silently lost, never spam":
 *
 *  - **Nothing is asked for while the unit is offline.** The voice socket cannot open, so an ask is
 *    a certain failure; it spends no budget either, because being out of signal for a while must
 *    not use up the alert's chances (`LiveSessionController.isOnline`, the same test the talk tap
 *    makes).
 *  - **A line gets [maxRequests] asks per episode, then holds until the engine comes back on.** The
 *    code is still untold, so the next ignition raises it again - it is deferred, not dropped, and
 *    the Logbook CODES tab shows it on screen the whole time either way.
 *
 * An ask that was GATED (mute, a call, onboarding unfinished) spends nothing either: the caller
 * only reports back what really left for the session controller. A fifteen-minute phone call must
 * not be what exhausts an overheat warning.
 *
 * Keyed by a string - a trouble code, or [OVERHEAT_KEY] - so one episode's budget is its own. A
 * key is settled (forgotten, full budget next time) when it is spoken, when the code clears, or
 * when the coolant comes back down.
 *
 * Pure and thread-safe: the scan loop reads it, `onSpoken` settles from whichever thread fed the
 * socket.
 */
internal class AlertRetryBudget(private val maxRequests: Int = MAX_REQUESTS) {
    private val spent = mutableMapOf<String, Int>()

    /**
     * Which of [keys] may be asked for now: none at all when [online] is false, otherwise those
     * that have not used up their asks for this episode.
     */
    @Synchronized
    fun requestable(keys: Set<String>, online: Boolean): Set<String> {
        if (!online) return emptySet()
        return keys.filterTo(mutableSetOf()) { (spent[it] ?: 0) < maxRequests }
    }

    /** Records that a line really went to the session controller for [keys]. */
    @Synchronized
    fun requested(keys: Set<String>) {
        keys.forEach { spent[it] = (spent[it] ?: 0) + 1 }
    }

    /** Forgets [keys]: spoken, or the episode ended (the code cleared, the coolant came down). */
    @Synchronized
    fun settled(keys: Set<String>) {
        keys.forEach { spent.remove(it) }
    }

    /**
     * The engine just came on: every held line may be asked for again. This is the "next ignition
     * or drive start" the bound promises, read from `TelemetryRecorder.isEngineRunning` (RPM over
     * zero) rather than GPS, because the unit's own GPS is dead and a phone-fed fix is not an
     * ignition.
     */
    @Synchronized
    fun onEngineStart() {
        spent.clear()
    }

    companion object {
        /** The key the coolant-overheat line budgets under; every other key is a trouble code. */
        const val OVERHEAT_KEY = "overheat"

        /**
         * One ask plus two retries, so a line that keeps failing stops inside about fifteen minutes
         * of scans instead of running for the whole drive.
         */
        const val MAX_REQUESTS = 3
    }
}
