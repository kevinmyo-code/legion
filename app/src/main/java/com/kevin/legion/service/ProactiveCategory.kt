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
 * [hasContent] is the honest half of the settings screen. A category with no raise wired to it
 * must say so, not hide, grey out, or fall silent (ticket 04 call 4) - same posture as the digest
 * builders' "not logged, never 0". **Flip this to `true` in the same commit that adds the
 * category's first raise**, never before. All five now have content: TIMING/FLEET/SAFETY shipped
 * with the proactive build itself, DIGEST got the scheduled sitrep (`.scratch/hands-and-senses/issues/22-build-the-sitrep.md`),
 * and WELLBEING got the scheduled checklist digest
 * (`.scratch/goal-plans/issues/05-wellbeing-digest.md`,
 * [com.kevin.legion.wellbeing.WellbeingDigestAlarmReceiver]).
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
     *
     * **First content, goal-plans ticket 05** (`.scratch/goal-plans/issues/05-wellbeing-digest.md`):
     * the scheduled wellbeing digest ([com.kevin.legion.wellbeing.WellbeingDigestAlarmReceiver])
     * raises through this category - one line, at a time Kevin picks, naming today's BIO checklist
     * items. **Scheduled digest ONLY, never per-item** (settled decision 6 of that map) - see
     * [com.kevin.legion.wellbeing.WellbeingDigestBuilder]'s own class doc for why its signature is
     * what enforces that rather than a convention alone.
     */
    WELLBEING("wellbeing", "Wellbeing", "Rest, sleep, and staying on top of what you set out to do.", true),

    /** The cars. Open recalls, an odometer milestone, a long drive without a break. */
    FLEET("fleet", "Fleet", "Your cars - recalls, milestones, and a nudge on a long drive.", true),

    /** A summary you asked to receive, at a time you chose. **First content, ticket 22
     * (`.scratch/hands-and-senses/issues/22-build-the-sitrep.md`): the scheduled sitrep**
     * ([com.kevin.legion.sitrep.SitrepAlarmReceiver]) raises through this category. `hasContent`
     * flips to `true` in the SAME commit that lands the alarm/receiver wiring, per this file's own
     * class doc rule and ticket 08's resolution §1/§7 - never before, and never a commit later. */
    DIGEST("digest", "Digest", "Round-ups and briefings, at a time you pick.", true);

    companion object {
        fun fromKey(key: String): ProactiveCategory? = entries.firstOrNull { it.key == key }

        /**
         * What an existing install with `muted = false` turns on at upgrade (ticket 04 call 3).
         *
         * **Kevin's effective behaviour must not change on upgrade**, so this is exactly the set the
         * eleven existing raises already map onto. Wellbeing and Digest stay off here even though
         * both now have content (see each category's own doc comment) - `hasContent` says whether a
         * switch governs anything at all, and is a separate question from whether flipping it ON
         * for an existing install without being asked would be a silent behaviour change. It would
         * be; this set is unchanged.
         *
         * A FRESH install gets none of these: master on, every category off (ticket 04 call 2). An
         * assistant that speaks first before being asked to is the thing people uninstall.
         */
        val CARRIED_OVER_ON_UPGRADE = setOf(SAFETY, TIMING, FLEET)
    }
}
