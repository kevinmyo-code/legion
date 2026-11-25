package com.kevin.midnightai.service

/**
 * When Moose says something about the weather turning rough on the road.
 *
 * The rule the drive monitor used to carry inline, lifted out so it is the tested thing (the same
 * move [AnnouncedCodes] made for the health scan). Three facts are decided here and nowhere else:
 *
 *  - **Only a real calm -> rough TRANSITION speaks.** "It was already raining when you got in" is
 *    the startup greeting's job; repeating it is the app noticing the weather at the driver.
 *  - **A mid-turn tick does not consume the transition.** When Moose or the driver is mid-sentence
 *    the reading is not taken at all, so the change is still news on the next quiet tick instead of
 *    being counted and silently swallowed.
 *  - **A trivia game does NOT hold it.** The line is [ProactiveKind.URGENT] (phone parity, see
 *    there), and an urgent line rides over a game; the break nudge, idle chatter and the milestone
 *    beside it in the drive monitor are routine and still wait.
 *
 * Pure (time and every gate are passed in), so all of it is a JVM test. The caller keeps the
 * prompt: what to say is its business, whether to say it is this.
 */
internal class RoughWeatherWatch(private val cooldownMs: Long = COOLDOWN_MS) {
    // Null until the first reading is taken, so the first tick of a process can never fire.
    private var lastCaution: Boolean? = null
    // Null, not 0: "never said one" is not "said one at epoch zero". The loop it replaced used 0
    // with a wall clock, where the difference never showed; it would silence the first 90 minutes
    // of any other clock.
    private var lastAlertAt: Long? = null

    /**
     * One drive-monitor tick. [caution] is the warm weather cache's current reading (null when
     * nothing has been fetched yet), [driving] whether a drive is in progress, [busy] whether a
     * conversation is running, [gameActive] whether a trivia game is.
     *
     * @return true when the rough-weather line should be asked for now.
     */
    fun onTick(
        caution: Boolean?,
        driving: Boolean,
        busy: Boolean,
        gameActive: Boolean,
        nowMs: Long,
    ): Boolean {
        // Deliberately before the reading is taken: see the class doc.
        if (busy || (gameActive && KIND.heldByGame)) return false

        val turnedRough = caution == true && lastCaution == false
        if (caution != null) lastCaution = caution

        if (!driving || !turnedRough) return false
        lastAlertAt?.let { if (nowMs - it < cooldownMs) return false }
        lastAlertAt = nowMs
        return true
    }

    companion object {
        /** Rough weather is SAFETY on the phone, so URGENT here. */
        val KIND = ProactiveKind.URGENT

        /**
         * Floor between rough-weather alerts. Generous on purpose: weather that flickers around the
         * caution threshold (drizzle the WMO code cannot make its mind up about) must not turn
         * Moose into a weather nag, which is the sec 9.1 failure mode where noticing becomes
         * chatter.
         */
        const val COOLDOWN_MS = 90 * 60 * 1000L
    }
}
