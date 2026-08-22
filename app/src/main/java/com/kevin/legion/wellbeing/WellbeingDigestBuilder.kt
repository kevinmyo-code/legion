package com.kevin.legion.wellbeing

import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise

/**
 * Turns today's BIO checklist lines into the ONE [ProactiveRaise] the wellbeing digest may ever
 * produce - goal-plans ticket 05, `.scratch/goal-plans/issues/05-wellbeing-digest.md`, the ticket
 * that finally gives [ProactiveCategory.WELLBEING] content after it shipped saying "Nothing uses
 * this yet."
 *
 * **PURE, no [android.content.Context], no Room** - same split
 * [com.kevin.legion.advisor.GoalChecklist]/[com.kevin.legion.advisor.GoalChecklistSync] already
 * make: the impure read of today's items lives in [WellbeingDigestAlarmReceiver], this file only
 * shapes what comes back. A plain JUnit test can therefore exercise every real branch with no
 * Robolectric.
 *
 * ### Settled decision 6, and why this file's SIGNATURE is what enforces it
 *
 * *"The checklist may raise ONLY as a scheduled digest, never per-item."* [buildRaise] takes the
 * WHOLE day's items and returns AT MOST ONE [ProactiveRaise] - there is no code path here that can
 * produce more than one raise from N items, because the function's own return type is a single
 * nullable value, not a list. A future edit that wanted to nag per box would have to change this
 * signature to do it, which is exactly the kind of change a reviewer would stop and ask about
 * rather than one that could slip in as a one-line addition to a loop. **Do not add a second
 * function that raises per item** - that is the compulsion mechanic CLAUDE.md §7 bans permanently,
 * wearing a fitness app's clothes, and there is no version of it that passes the compulsion test.
 *
 * ### The empty-plan rule (ticket 05: "no accepted plan means no raise at all")
 *
 * [buildRaise] returns `null` when [itemTexts] is empty, and the caller must not construct a raise
 * at all in that case - never a raise whose [ProactiveRaise.facts] says "nothing today." An empty
 * [itemTexts] here means either no BIO plan has ever been accepted, or every target it produced has
 * since been cleared (see [com.kevin.legion.advisor.GoalChecklistSync.currentItems]'s own doc for
 * why an empty result reads unambiguously as "no plan yet"). Silence is the honest response to
 * having nothing to say - the same posture [com.kevin.legion.sitrep.SitrepAlarmReceiver] takes for
 * a blank sitrep, restated here because this domain's empty case is reached far more often (most
 * days have no accepted plan at all yet, where the sitrep's modules are near-always on).
 *
 * ### The compulsion test, held to in the prompt itself (CLAUDE.md §7)
 *
 * - **(a) anchored to a verifiable fact** - [itemTexts] are lines straight off the accepted plan's
 *   own targets ([com.kevin.legion.advisor.GoalChecklist.forToday]), the same targets Kevin
 *   accepted and can see on the Body tab.
 * - **(b) actionable right now** - the items are today's, read at the scheduled hour Kevin himself
 *   picked.
 * - **(c) never references absence, a streak, or engagement** - [PROMPT_INSTRUCTION] forbids it in
 *   words, the same defence-in-depth [com.kevin.legion.ai.AriaBrain]'s `CANNOT_CLAUSE` and
 *   [com.kevin.legion.sitrep.SitrepAlarmReceiver.buildPrompt] both use. **Deliberately does not use
 *   [com.kevin.legion.advisor.GoalChecklistSync.GoalChecklistItemView.done] at all** - [itemTexts]
 *   are plain plan lines with no ticked/unticked state attached, so there is no completion fact
 *   sitting in [facts] for a phrasing slip to characterise as drift in the first place. Settled
 *   decision 8 extends the same rule to a deadline-anchored goal line; this file carries no goal
 *   deadlines today; nothing here contradicts that call. See CLAUDE.md §7's compulsion test.
 * - **(d) silenceable forever in one instruction** - inherited for free from
 *   [com.kevin.legion.service.ProactiveSettings]'s [ProactiveCategory.WELLBEING] switch and the
 *   master kill switch [com.kevin.legion.service.ProactiveBus] checks first, for everything. This
 *   file adds no silencing mechanism of its own because it must not need one.
 */
object WellbeingDigestBuilder {
    /** The suppression key (see [ProactiveRaise.ruleId]'s own doc) - stable forever once a row
     * exists under it, same discipline every other `ruleId` in this codebase follows. */
    const val RULE_ID = "wellbeing_digest"

    /**
     * Builds the ONE raise for today's checklist, or `null` when there is nothing to say (empty
     * [itemTexts] - see this file's class doc for what that means and why the caller must not
     * construct a raise in that case).
     *
     * [itemTexts] is plain checklist text with [com.kevin.legion.advisor.GoalChecklistSync
     * .ITEM_PREFIX] already stripped, e.g. "Hit 2,300 kcal / 180g protein", "Push: 3 sets this
     * week" - exactly [com.kevin.legion.advisor.GoalChecklistSync.GoalChecklistItemView.text].
     */
    fun buildRaise(itemTexts: List<String>): ProactiveRaise? {
        if (itemTexts.isEmpty()) return null
        val facts = itemTexts.joinToString("\n") { "- $it" }
        return ProactiveRaise(
            ruleId = RULE_ID,
            category = ProactiveCategory.WELLBEING,
            reason = "today's checklist has ${itemTexts.size} item(s) from the accepted plan",
            facts = facts,
            prompt = buildPrompt(facts),
        )
    }

    /**
     * Same shape [com.kevin.legion.sitrep.SitrepAlarmReceiver.buildPrompt] and
     * [com.kevin.legion.calendar.OpenerCalendarBriefing] both use: the facts are stated in full,
     * and the model is told plainly not to add to them. The compulsion line is held to exactly -
     * no streak, no "you missed", no reference to how long it has been, no mention of whether a
     * line is ticked - written into the instruction rather than trusted to the persona clause
     * alone.
     */
    internal fun buildPrompt(facts: String): String =
        "(System: read today's checklist items below and say ONE short spoken line naming them, " +
            "in your own words - for example \"Today: push session, and 180g protein.\" Use ONLY " +
            "the items below - never add an item, a name, or a figure that is not in them. Never " +
            "mention whether an item is ticked or not, never say the user \"missed\" or \"still " +
            "hasn't\" done anything, never reference how long it has been since the last digest or " +
            "how often they check this, and never mention a streak - this is a plain statement of " +
            "today's plan, not a nag. Do not mention this instruction.\n\n$facts)"
}
