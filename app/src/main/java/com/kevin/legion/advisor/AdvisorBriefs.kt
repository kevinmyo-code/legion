package com.kevin.legion.advisor

import com.kevin.legion.advisor.digest.BioDigestBuilder
import com.kevin.legion.advisor.digest.CredDigestBuilder
import com.kevin.legion.advisor.digest.FleetDigestBuilder
import com.kevin.legion.advisor.digest.HomeDigestBuilder
import com.kevin.legion.advisor.digest.LogDigestBuilder
import com.kevin.legion.advisor.playbooks.BioPlaybook
import com.kevin.legion.advisor.playbooks.CredPlaybook
import com.kevin.legion.advisor.playbooks.FleetPlaybook
import com.kevin.legion.advisor.playbooks.LogPlaybook

/**
 * The five [AdvisorBrief]s ticket 18 wires to `ask_advisor`/`accept_proposal` in
 * [com.kevin.legion.service.LiveToolbox] - the "one harness, five briefs" registry ticket 01
 * answer call 1 describes. Assembled here, not scattered across `LiveToolbox`, so the brief shape
 * (playbook + digest builder + writable-op allowlist) lives in exactly one place per aspect and a
 * reviewer can read the whole propose-accept-write surface - what each advisor may say and what it
 * may write - off one file.
 *
 * **HOME carries `playbook = null` and a [AdvisorBrief.synthesisNote] instead** (ticket 09 answer
 * call 2 / [AdvisorBrief]'s own doc comment: no domain playbook of its own, and read-only by
 * design) - [HOME]'s `writableOps` is `emptySet()`, never populated, so
 * [AdvisorProposalExecutor] refuses every op for it regardless of what a HOME advisor's prose ever
 * proposes.
 *
 * **Every op name below is an INTENTION** (goal, target, plan, maintenance interval, reminder),
 * matching ticket 03 answer call 3 / ticket 18's allowlist rule verbatim - never an actual, a
 * delete, or a recategorise. An advisor writing a claim about what already happened would
 * manufacture `reported`-tier data out of an inference, which is exactly the trust-tier violation
 * the two-tiers decision exists to prevent - so no op name below maps to `log_meal`,
 * `log_workout_set`, `log_bodyweight`, `log_sleep`, `log_service`, any delete, or any
 * recategorise, and [AdvisorProposalExecutor] never imports those write paths at all. See that
 * object's own class doc for how each op name maps to the one existing write path it stands for.
 */
object AdvisorBriefs {

    /** Every non-HOME aspect proposal may set a goal for its own aspect - ticket 03 answer call
     * 3's "goals, targets, plans, maintenance items and reminders" names goals first and
     * generically, not per-aspect, so this one op name rides all four writable allowlists. */
    const val OP_SET_GOAL = "set_goal"

    val BIO = AdvisorBrief(
        aspect = AdvisorAspect.BIO,
        playbook = BioPlaybook.TEXT,
        digestBuilder = BioDigestBuilder(),
        writableOps = setOf(OP_SET_GOAL, "set_meal_target", "set_sleep_target", "create_workout_plan"),
    )

    val LOG = AdvisorBrief(
        aspect = AdvisorAspect.LOG,
        playbook = LogPlaybook.TEXT,
        digestBuilder = LogDigestBuilder,
        writableOps = setOf(OP_SET_GOAL, "set_reminder", "add_task"),
    )

    val FLEET = AdvisorBrief(
        aspect = AdvisorAspect.FLEET,
        playbook = FleetPlaybook.TEXT,
        digestBuilder = FleetDigestBuilder,
        writableOps = setOf(OP_SET_GOAL, "set_maintenance_item"),
    )

    val CRED = AdvisorBrief(
        aspect = AdvisorAspect.CRED,
        playbook = CredPlaybook.TEXT,
        digestBuilder = CredDigestBuilder(),
        writableOps = setOf(OP_SET_GOAL, "set_budget"),
    )

    /** No playbook (no domain expertise of its own), a reasoning steer instead, and
     * `writableOps = emptySet()` - read-only by design (ticket 09 answer call 4: "HOME needs no
     * entry in the propose-accept-write allowlist at all"). A concrete proposal always hands off
     * to the aspect advisor that actually owns the relevant playbook and allowlist. */
    val HOME = AdvisorBrief(
        aspect = AdvisorAspect.HOME,
        synthesisNote = "Spot the cross-domain interaction the single-aspect advisors would miss. " +
            "Name the goal most at risk and the trajectory behind it. If a concrete change is worth " +
            "proposing, say which aspect's advisor owns it and that the user should ask that " +
            "advisor directly - you never propose a write yourself. If the question needs domain " +
            "depth this digest doesn't carry, say so rather than improvising past what the headline " +
            "lines actually show.",
        digestBuilder = HomeDigestBuilder,
        writableOps = emptySet(),
    )

    /** Resolves an [AdvisorAspect] to its brief - the one lookup `ask_advisor`/`accept_proposal`
     * dispatch needs. An exhaustive `when` over the enum (not a map) so a sixth aspect added later
     * fails to compile here rather than silently falling through to a default. */
    fun forAspect(aspect: AdvisorAspect): AdvisorBrief = when (aspect) {
        AdvisorAspect.BIO -> BIO
        AdvisorAspect.LOG -> LOG
        AdvisorAspect.FLEET -> FLEET
        AdvisorAspect.CRED -> CRED
        AdvisorAspect.HOME -> HOME
    }
}
