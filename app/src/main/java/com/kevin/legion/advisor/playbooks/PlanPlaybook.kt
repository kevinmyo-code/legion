package com.kevin.legion.advisor.playbooks

/**
 * PLAN recommender playbook: SHAPE doctrine for [com.kevin.legion.advisor.GoalPlanAgent], the
 * sub-agent that turns a prose BIO goal ("lose fat, gain muscle") into concrete targets.
 *
 * **Split ownership, decided by Kevin 2026-08-21 (overriding this ticket's original draft, which
 * duplicated BIO's numeric bands here).** [BioPlaybook] stays the sole authority on the NUMBERS -
 * protein ranges, deficit/surplus bands, volume landmarks. This file owns only the SHAPE of
 * turning a goal into a handful of targets: how to reach a starting calorie estimate, how to hand
 * off the workout piece, how to hedge exactly once, and when to refuse a target rather than the
 * whole plan. [com.kevin.legion.advisor.GoalPlanAgent] primes BOTH playbooks concatenated
 * ([com.kevin.legion.advisor.Priming.combinedText]) rather than either alone - restating BIO's
 * bands here would be a second copy of the same numbers to keep in sync, which is the exact
 * failure two stores of editable doctrine invites.
 *
 * `requiredPhrases` on [com.kevin.legion.advisor.PrimingTopic.PLAN] still guards this file's own
 * professional-referral boundaries, worded to MATCH [BioPlaybook]'s wording rather than invent a
 * second phrasing - a driver's edit that deletes them here is refused exactly like an edit that
 * deletes them from BIO.
 *
 * **The 800 kcal/day floor named in this text is a backstop, not the only guard.**
 * [com.kevin.legion.advisor.GoalPlanAgent.parse] enforces the same number in Kotlin,
 * unconditionally, because a playbook edit (even one that keeps every `requiredPhrases` substring)
 * could reword this paragraph into something that reads fine and still lets the number through - a
 * substring check cannot catch a rewording that keeps the words and drops the meaning. See that
 * function's own doc comment.
 */
object PlanPlaybook {
    const val TEXT = """
Turns a prose BIO goal into a plan SHAPE - calorie/macro target, sleep target, workout goal
sentence, goal lines. Numeric doctrine lives in BIO, primed alongside this one - apply its bands,
never restate or re-derive them here.

MAINTENANCE ESTIMATE: Mifflin-St Jeor scaled by a stated activity multiplier, both guesses - say so
ONCE in rationale, never per field, and never promise an adjustment the app will not perform;
invite, don't commit ("worth revisiting once you have a couple of weeks of weight data"). Apply
BIO's bands against TOTAL BODYWEIGHT - never ask for or estimate body fat.

WORKOUT: hand a plain-language goal sentence (movements, a rough session count from stated
time/equipment) to the workout planner; never enumerate exercises or sets yourself.

REFUSAL, one target at a time: a target crossing a boundary is refused ON ITS OWN - generate the
rest, omit that field, say plainly which was refused and why. Never fail the whole plan. A named
medical condition refuses only the target it affects; point at a professional, keep the rest.

BOUNDARIES, worded as BIO's own: Pain or injury - refuse training, see a medical professional.
Medical conditions or medications (diabetes, heart, blood pressure, pregnancy, GLP-1 or a
weight-affecting prescription) - refuse the target, a physician or dietitian owns it.
Disordered-eating signals - refuse, name the concern gently. Minors, and any SARMs/steroid/
stimulant request - decline. A calorie target at or below 800 kcal/day is a medically supervised
intervention - refused outright, matching the app's own hard floor.
"""
}
