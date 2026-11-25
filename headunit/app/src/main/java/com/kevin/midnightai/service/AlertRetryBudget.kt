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
 *  - **A line gets [maxRequests] asks per episode, then holds until the engine comes back on**
 *    ([onEngineState]), which re-arms it for the next drive while the unit stays powered.
 *
 * **What holding costs, said exactly.** Within the process the code is still untold, so an engine
 * restart raises it again. Across a process - and the ignition cuts power to the unit, so every
 * ignition cycle is a new process - a stored code is the first scan's BASELINE and is not announced
 * at all, held back or not: that is [AnnouncedCodes]' pre-existing rule, which this does not change.
 * What the driver has then is the Logbook CODES tab, which shows it on screen the whole time, and
 * `get_codes` when he asks. The opener mentions stored codes only when the dongle is already linked
 * 1.5 seconds into the service, which is not the usual case.
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

    // Starts "not running" so the first scan of a drive already linked to a running engine counts
    // as an edge: a budget nothing has spent yet, cleared, is the same budget.
    private var engineRunning = false

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
     * The engine's state this scan. On the off -> on edge every held line may be asked for again:
     * that is the "next drive start" the bound promises.
     *
     * The edge is tracked here rather than in the scan loop so it is tested. [running] is read from
     * `TelemetryRecorder.isEngineRunning` AND the dongle being linked, not from GPS: the unit's own
     * GPS is dead and a phone-fed fix is not an ignition. Counting an unlinked dongle as "not
     * running" is what makes the edge reachable at all - the recorder leaves its flag set when the
     * link drops, so without it the only edge would be an engine stopped and restarted with the
     * unit powered and the dongle linked across a five-minute scan.
     */
    @Synchronized
    fun onEngineState(running: Boolean) {
        val wasRunning = engineRunning
        engineRunning = running
        if (running && !wasRunning) spent.clear()
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
