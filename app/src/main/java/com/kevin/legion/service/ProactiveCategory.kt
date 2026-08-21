package com.kevin.legion.service

/**
 * The five kinds of unprompted speech, each with its own switch (settled decision 1,
 * `.scratch/proactive-mode/map.md`, Kevin 2026-08-16). Two states each, no tri-state.
 *
 * **Every raise in LEGION maps onto exactly one of these.** Nobody invents a sixth switch or their
 * own opt-in - that is what settled decision 1 exists to prevent, and it is why this enum is the
 * only vocabulary [ProactiveBus] accepts.
 *
 * The master switch ([ProactiveSettings.master]) ANDs over all five and has **no exemptions, safety
 * included** (settled decision 2). Off means silent. A switch that does not fully silence is a
 * switch nobody believes, and belief in it is what makes proactivity acceptable at all.
 *
 * [hasContent] is the honest half of the settings screen. Wellbeing and Digest have **no raises
 * today**, and a switch that governs nothing must not imply it does (ticket 04 call 4) - the row
 * says so in words rather than hiding, grey-out, or silence. Same posture as the digest builders'
 * "not logged, never 0". **Flip this to `true` in the same commit that adds the category's first
 * raise**, never before.
 */
enum class ProactiveCategory(
    /** Stable storage key. Written to `proactive_settings.key` and to the raise history, so it
     * must never change once a row exists under it. */
    val key: String,
    /** Row title on the settings screen. */
    val title: String,
    /** One line under the title saying what this category actually says out loud. */
    val blurb: String,
    /** False while no raise anywhere maps onto this category - see the class doc. */
    val hasContent: Boolean,
) {
    /**
     * Something is wrong right now and waiting would cost you. Coolant overheat, a new trouble
     * code, conditions turning rough while driving.
     *
     * **The only category outside the daily cap** (ticket 05 call 3): a real warning must never
     * lose its slot to a rest nudge. Uncapped still means inside the master switch and inside
     * quiet hours' own carve-out - see [ProactiveSettings.mayRaise].
     */
    SAFETY("safety", "Safety", "Warnings that should not wait - overheating, a new fault, rough conditions.", true),

    /** Something is due, or you have arrived somewhere it matters. Fired reminders, place arrival,
     * an incoming call, the startup greeting. */
    TIMING("timing", "Timing", "Reminders coming due, arrivals, calls, and the greeting when you open the app.", true),

    /**
     * Rest, sleep, and keeping on track. The category this whole map came from - Kevin, 2026-08-16:
     * *"Much like Alfred does to Batman - it's past 10pm, perhaps rest is in order."*
     *
     * **The one category allowed to speak inside quiet hours** (ticket 05 call 1), because the
     * nudge it exists for lives at night. A window that muted the night would have killed the line
     * this map was chartered to build.
     */
    WELLBEING("wellbeing", "Wellbeing", "Rest, sleep, and staying on top of what you set out to do.", false),

    /** The cars. Open recalls, an odometer milestone, a long drive without a break. */
    FLEET("fleet", "Fleet", "Your cars - recalls, milestones, and a nudge on a long drive.", true),

    /** A summary you asked to receive, at a time you chose. Nothing raises here yet; whether it
     * subsumes the morning brief or merely delivers it is still open on the map. */
    DIGEST("digest", "Digest", "Round-ups and briefings, at a time you pick.", false);

    companion object {
        fun fromKey(key: String): ProactiveCategory? = entries.firstOrNull { it.key == key }

        /**
         * What an existing install with `muted = false` turns on at upgrade (ticket 04 call 3).
         *
         * **Kevin's effective behaviour must not change on upgrade**, so this is exactly the set the
         * eleven existing raises already map onto. Wellbeing and Digest stay off because they have
         * no content - turning on a switch that governs nothing would be a silent promise.
         *
         * A FRESH install gets none of these: master on, every category off (ticket 04 call 2). An
         * assistant that speaks first before being asked to is the thing people uninstall.
         */
        val CARRIED_OVER_ON_UPGRADE = setOf(SAFETY, TIMING, FLEET)
    }
}
