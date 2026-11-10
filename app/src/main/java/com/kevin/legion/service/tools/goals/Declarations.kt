@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.goals

import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.advisor.AdvisorBriefs
import com.kevin.legion.advisor.GoalPlanAgent
import com.kevin.legion.advisor.HarnessPrompt
import com.kevin.legion.goals.GoalController
import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The goals tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        // --- Goals (ticket 19, `.scratch/aspect-advisors/issues/02-goal-store.md`'s Answer) -----
        //
        // Three tools, not one per aspect: a goal is uniformly statement + aspect + optional
        // number (answer call 1/2), so one `aspect` enum parameter on each tool covers all four
        // writable aspects rather than multiplying the declaration count by four. That keeps this
        // addition to +3 tools (~71 -> ~74), not +12. `aspect` uses the plain-string vocabulary
        // GoalController.ASPECTS carries (matching Goal.aspect's own TEXT column and
        // AdvisorAspect's `key`s minus HOME, which has no goals of its own - see
        // GoalController's doc comment) rather than the tab labels the driver actually says, so
        // every description below spells the mapping out for Gemini in words.
        //
        // No confirm step, matching set_budget/set_meal_target/log_bodyweight - CLAUDE.md's
        // existing direct-dictation posture for every other write tool in this file, and ticket
        // 19's brief is explicit that goals follow the same pattern (the propose-accept protocol
        // is for ADVISOR-authored writes only, a different ticket).
        add(fn(
            name = "set_goal",
            description = "Set or update a long-term goal for one aspect of the user's life - " +
                "not a daily or weekly target, a standing intention like 'get to 175 lbs', 'save " +
                "$30k by 2028', or 'ship the deck'. A NUMBER IS OPTIONAL: most goals are prose " +
                "only, and a goal with no number is a normal, complete goal, never a broken one - " +
                "never invent a fake number just to fill target_value. Restating an existing goal " +
                "for the same aspect with the same metric_key (e.g. giving the savings goal a new " +
                "dollar figure) REVISES it rather than creating a duplicate; call list_goals first " +
                "if unsure whether one already exists.",
            params = obj(
                "aspect" to schema(
                    "string",
                    "Which aspect this goal belongs to: bio (fitness/nutrition/sleep), log " +
                        "(notes, lists, planning), fleet (the car), or cred (personal finance).",
                    enum = GoalController.ASPECTS,
                ),
                "statement" to schema("string", "The goal in the user's own words. Always required, even alongside a number."),
                "target_value" to schema("number", "Optional target number, e.g. 175 or 30000. Omit for a prose-only goal."),
                "unit" to schema("string", "Unit for target_value, e.g. 'lbs' or 'usd'. Omit if target_value is omitted."),
                "metric_key" to schema(
                    "string",
                    "Optional known metric this goal tracks for automatic progress math, e.g. " +
                        "'bodyweight_lbs', 'savings_balance_cents', 'odometer_miles'. Leave unset " +
                        "for a goal with no app-tracked metric - most goals should leave this unset.",
                ),
                "deadline" to schema("string", "Optional deadline, month/day/year, e.g. 08/07/2026."),
            ),
            required = listOf("aspect", "statement"),
        ))

        add(fn(
            name = "list_goals",
            description = "List the user's current active goals. Omit aspect to list every " +
                "goal across every aspect at once - use that form for a broad 'how am I doing on " +
                "my goals' question.",
            params = obj(
                "aspect" to schema(
                    "string",
                    "Which aspect to list. Omit for every aspect at once.",
                    enum = GoalController.ASPECTS,
                ),
            ),
            required = listOf(),
        ))

        add(fn(
            name = "close_goal",
            description = "Mark a goal achieved or abandoned. Identify it by aspect plus a few " +
                "words from its own statement, e.g. 'savings' or 'the old car' - if more than one " +
                "active goal in that aspect matches, this reports every match instead of guessing, " +
                "so ask the user which one they mean rather than picking.",
            params = obj(
                "aspect" to schema("string", "Which aspect the goal belongs to.", enum = GoalController.ASPECTS),
                "statement" to schema("string", "A few words from the goal's statement, enough to identify it uniquely."),
                "status" to schema("string", "How it ended. Defaults to achieved.", enum = listOf("achieved", "abandoned")),
            ),
            required = listOf("aspect", "statement"),
        ))

        // --- Goal plans (ticket 03, `goal-plans`: "revision by conversation") -------------------
        //
        // Two tools, matching GoalPlanAgent's own generate()/accept() split (ticket 02) rather
        // than collapsing them into one: generate_goal_plan NEVER writes anything (a plan is a
        // PROPOSAL until the user gives one plain yes to the whole thing - settled decision 14),
        // and accept_goal_plan is the only place the workout piece is actually written. The meal
        // target, sleep target, and any long-term goals a plan proposes are deliberately NOT
        // applied by either of these - the model calls set_meal_target/set_sleep_target/set_goal
        // itself with the values generate_goal_plan handed back, the same tools direct voice
        // dictation already uses (settled decision 9: nothing new gets write access).
        //
        // Revision has NO separate tool. "I don't have gym access, mix in kettlebells" calls
        // generate_goal_plan again - a revision is a NEW PLAN, regenerated from the original goal
        // prose plus every constraint stated so far (ticket 03's own settled call 1), never a diff
        // against something stored, because nothing is stored: ticket 02 deliberately built no
        // plan persistence, and ticket 04 owns that. new_constraint is what makes a stated fact
        // survive to the NEXT regeneration, even in a later session, without the user repeating
        // it - see GoalPlanAgent.CONSTRAINT_PREFIX's doc comment for where it actually lives.
        add(fn(
            name = "generate_goal_plan",
            description = "Turn a BIO goal, in the user's own words, into a rough, loosely-" +
                "followable plan - a daily calorie/macro target, a nightly sleep target, a " +
                "workout goal sentence, and one or more long-term goals. This does not need to " +
                "be precise, and never presents a number as more exact than it is. Use it for a " +
                "BRAND-NEW goal ('lose fat, gain muscle') and for REVISING one already discussed " +
                "('I don't have gym access, can we mix in kettlebells') - for a revision, restate " +
                "the ORIGINAL goal in full in goal_text, never just the change being asked for; " +
                "this always regenerates the whole plan rather than editing one field in place, " +
                "so a revised calorie target never ends up paired with a workout piece it no " +
                "longer matches. If the user is stating a new fact or limit that should keep " +
                "applying going forward - no gym access, a dietary restriction, only free on " +
                "weekends - pass it in new_constraint so it is remembered and folded into every " +
                "future plan without them repeating it; leave new_constraint out entirely when " +
                "there is nothing new to remember. This call NEVER writes anything - it only " +
                "proposes. Speak the rationale and every field returned, get ONE plain yes for " +
                "the WHOLE plan, and only then call set_meal_target, set_sleep_target, and " +
                "set_goal for the matching fields plus accept_goal_plan for the workout piece - " +
                "never say a target is set, in effect, or being tracked before those calls have " +
                "actually run. Any target that would cross a safety boundary comes back in " +
                "refusals instead of as a field - say plainly which target was refused and why, " +
                "and still offer everything else the plan proposed.",
            params = obj(
                "goal_text" to schema(
                    "string",
                    "The BIO goal in the user's own words - the full goal, every time, even on a " +
                        "revision.",
                ),
                "new_constraint" to schema(
                    "string",
                    "A new fact or limit the user just stated that should keep applying to " +
                        "future plans, e.g. 'no gym access' or 'vegetarian'. Omit entirely when " +
                        "nothing new was said.",
                ),
            ),
            required = listOf("goal_text"),
        ))

        add(fn(
            name = "accept_goal_plan",
            description = "Applies the WORKOUT piece of a plan generate_goal_plan already " +
                "proposed - call this ONLY after the user has given one plain yes to that whole " +
                "plan, never before. Pass workout_goal exactly as generate_goal_plan returned it " +
                "in the plan's own workoutGoal field; omit workout_goal entirely when that plan " +
                "had no workout piece, which is a normal, complete plan, not an error. The meal " +
                "target, sleep target, and any long-term goals from the same plan are NOT applied " +
                "here - call set_meal_target, set_sleep_target, and set_goal directly with the " +
                "values generate_goal_plan returned, exactly as for any other write.",
            params = obj(
                "workout_goal" to schema(
                    "string",
                    "The workoutGoal sentence generate_goal_plan returned for this plan. Omit if " +
                        "that plan proposed no workout piece.",
                ),
            ),
            required = listOf(),
        ))

        // --- Advisors (ticket 18, `.scratch/aspect-advisors/issues/18-build-ask-advisor-and-
        // accept.md`) -------------------------------------------------------------------------
        //
        // ONE tool, not five (ticket 01 answer call 2/ticket 18's own brief: "every declaration is
        // prompt tokens on every live session"). `aspect` covers bio/log/fleet/cred plus home for
        // an overall or cross-cutting question - AdvisorBriefs.forAspect resolves which brief
        // (playbook + digest builder + writable-op allowlist) actually runs. Measured +239 tokens
        // for this pair (ticket 11), judged acceptable.
        add(fn(
            name = "ask_advisor",
            description = "Hand off to a domain advisor for coaching, planning, budgeting, or " +
                "maintenance-planning advice grounded in the user's own record - it reasons from " +
                "a deterministic digest of what's actually logged, never a live web lookup, and " +
                "stays inside stated professional-referral boundaries rather than diagnosing or " +
                "prescribing past them. Pick 'bio' for fitness/nutrition/sleep coaching, 'log' for " +
                "planning/task/time-management advice, 'fleet' for maintenance planning, 'cred' for " +
                "personal-finance coaching and budgeting, and 'home' for anything OVERALL or CROSS-" +
                "CUTTING - a broad 'how am I doing' question, one that spans more than one aspect, " +
                "or whenever you are not sure which single aspect fits. ${HarnessPrompt.LATENCY_HINT} " +
                "The advisor may offer to write something back (a goal, a target, a plan, a " +
                "maintenance interval, a reminder) - it never writes anything itself. If it does, " +
                "tell the user plainly what it is proposing, and only call accept_proposal once " +
                "they say a clear yes to exactly that - never assume, and never call accept_proposal " +
                "on your own initiative.",
            params = obj(
                "aspect" to schema(
                    "string",
                    "Which advisor to ask: bio, log, fleet, cred, or home for overall/cross-cutting.",
                    enum = AdvisorAspect.values().map { it.key },
                ),
                "question" to schema("string", "The user's question, in their own words."),
            ),
            required = listOf("aspect", "question"),
        ))

        add(fn(
            name = "accept_proposal",
            description = "Write the proposal a domain advisor just offered, EXACTLY as it was " +
                "proposed. Call this only after the user gives a plain, explicit yes to it - never " +
                "on your own initiative. This executes the STORED proposal by its id, never values " +
                "you recall or restate yourself - you cannot pass or change any of its numbers here. " +
                "If the user wants something different from what was proposed ('yes, but 3 days " +
                "not 4'), do NOT call this - ask the advisor again with the change and get a fresh " +
                "proposal to accept instead.",
            params = obj(
                "id" to schema("integer", "The advice id the proposal came back under, from ask_advisor's response."),
            ),
            required = listOf("id"),
        ))
    }
}
