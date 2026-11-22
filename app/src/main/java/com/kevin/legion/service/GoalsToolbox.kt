package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.advisor.AdvisorProposalExecutor
import com.kevin.legion.advisor.GoalPlan
import com.kevin.legion.advisor.GoalPlanAgent
import com.kevin.legion.advisor.GoalPlanConstraints
import com.kevin.legion.advisor.GoalPlanResult
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.goals.GoalController
import com.kevin.legion.service.LiveToolbox.GOAL_CONSTRAINT_READ_THROUGH_REFUSAL
import com.kevin.legion.service.LiveToolbox.PENDING_DATE_FORMAT
import com.kevin.legion.service.LiveToolbox.rememberBlockedByReadThroughTool
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.util.shortDate
import com.kevin.legion.workouts.WorkoutController
import org.json.JSONArray
import org.json.JSONObject

/**
 * The goal tools' dispatch, split out of [LiveToolbox] (shrink pass, 2026-11-22) along the
 * [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its guards, then asks this object,
 * which answers the names it owns and returns null for every other. The declarations stay in
 * `service/tools/`; only the routing and the handlers live here.
 */
object GoalsToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(
        context: Context,
        name: String,
        args: JSONObject,
        touchedReadThroughToolThisTurn: Boolean = false,
    ): JSONObject? = when (name) {
        "set_goal" -> setGoalTool(context, args)
        "list_goals" -> listGoalsTool(context, args)
        "close_goal" -> closeGoalTool(context, args)
        "generate_goal_plan" -> generateGoalPlanTool(context, args, touchedReadThroughToolThisTurn)
        "accept_goal_plan" -> acceptGoalPlanTool(context, args)
        else -> null
    }

    // --- Goals (ticket 19) --------------------------------------------------

    /** The driver never says `bio`/`log`/`fleet`/`cred` - these back every goal-tool confirmation
     * phrase into the words they'd actually recognize, matching each aspect's tab label. */
    private fun aspectLabel(aspect: String): String = when (aspect) {
        "bio" -> "Body"
        "log" -> "Notes"
        "fleet" -> "Fleet"
        "cred" -> "Money"
        else -> aspect
    }

    private suspend fun setGoalTool(context: Context, args: JSONObject): JSONObject {
        val aspect = args.optString("aspect").trim().lowercase()
        if (aspect !in GoalController.ASPECTS) return result(false, "Which aspect - body, notes, fleet, or money?")
        val statement = args.optString("statement").trim()
        if (statement.isBlank()) return result(false, "What's the goal?")
        val targetValue = if (args.has("target_value") && !args.isNull("target_value")) args.optDouble("target_value") else null
        val unit = args.optString("unit").trim().takeIf { it.isNotBlank() }
        val metricKey = args.optString("metric_key").trim().takeIf { it.isNotBlank() }
        val deadlineRaw = args.optString("deadline").trim()
        val deadlineEpoch = if (deadlineRaw.isBlank()) null else try {
            // Same MM/dd/yyyy convention as log_sleep's `date` param, device zone - see
            // PENDING_DATE_FORMAT's own doc comment.
            java.time.LocalDate.parse(deadlineRaw, PENDING_DATE_FORMAT)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            return result(false, "I couldn't read that deadline - try month/day/year, like 08/07/2026.")
        }

        return when (val outcome = GoalController.setGoal(
            context, aspect = aspect, statement = statement, targetValue = targetValue,
            unit = unit, metricKey = metricKey, deadlineEpoch = deadlineEpoch,
        )) {
            is GoalController.SetOutcome.Created -> result(true, "Goal set for ${aspectLabel(aspect)}: $statement.")
            is GoalController.SetOutcome.Revised -> result(
                true,
                "Updated the ${aspectLabel(aspect)} goal: $statement. Previous: \"${outcome.previous.statement}\".",
            )
            // `changed: false`: nothing to confirm aloud (the phone's done line reads this field, 2026-11-19).
            is GoalController.SetOutcome.Unchanged ->
                result(true, "That's already the current ${aspectLabel(aspect)} goal - nothing changed.")
                    .put("changed", false)
        }
    }

    private suspend fun listGoalsTool(context: Context, args: JSONObject): JSONObject {
        val aspectRaw = args.optString("aspect").trim().lowercase()
        val goals = if (aspectRaw.isBlank()) {
            GoalController.allCurrentGoals(context)
        } else {
            if (aspectRaw !in GoalController.ASPECTS) return result(false, "Which aspect - body, notes, fleet, or money?")
            GoalController.currentGoals(context, aspectRaw)
        }
        val arr = JSONArray()
        for (g in goals) {
            val o = JSONObject()
                .put("aspect", g.aspect)
                .put("statement", g.statement)
                // Named explicitly so the model never has to infer "prose vs measurable" from the
                // mere presence of a JSON key - CLAUDE.md §4 rule 5's "say it in words" applied to
                // the tool's own output, not just user-facing strings.
                .put("measurable", g.targetValue != null)
            g.targetValue?.let { o.put("targetValue", it) }
            g.unit?.let { o.put("unit", it) }
            g.deadlineEpoch?.let { o.put("deadline", shortDate(it)) }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", goals.size).put("goals", arr)
    }

    private suspend fun closeGoalTool(context: Context, args: JSONObject): JSONObject {
        val aspect = args.optString("aspect").trim().lowercase()
        if (aspect !in GoalController.ASPECTS) return result(false, "Which aspect - body, notes, fleet, or money?")
        val query = args.optString("statement").trim()
        if (query.isBlank()) return result(false, "Which goal? Give me a few words from it.")
        val status = if (args.optString("status", "achieved").trim().lowercase() == "abandoned") "abandoned" else "achieved"

        return when (val outcome = GoalController.closeGoal(context, aspect, query, status)) {
            is GoalController.CloseOutcome.Closed -> result(true, "Closed ($status): ${outcome.goal.statement}.")
            GoalController.CloseOutcome.NotFound -> result(false, "I don't see an active ${aspectLabel(aspect)} goal matching that.")
            is GoalController.CloseOutcome.Ambiguous -> result(
                false,
                "More than one goal matches: ${outcome.matches.joinToString("; ") { it.statement }}. Which one?",
            )
        }
    }

    // --- Goal plans (ticket 03, `goal-plans`) -----------------------------------------------------

    /**
     * Stated goal-plan constraints, on the engine as the member's own memories since 2026-11-09
     * ([GoalPlanConstraints]; before that, the legacy `companion_memories` table). Built over
     * [MemoryToolbox.controllerFactory] so the `memory` tool, the Memory screen and this share one
     * controller and one test seam.
     */
    private fun goalPlanConstraints(context: Context): GoalPlanConstraints =
        GoalPlanConstraints.forContext(context, MemoryToolbox.controllerFactory(context))

    /**
     * `generate_goal_plan`. Persists [args]' `new_constraint` (if any) BEFORE generating, so the
     * very plan this call returns already reflects it - a driver who states a constraint and asks
     * for a plan in the same breath should not need to ask again to see it applied. Folds every
     * constraint on file (including the one just added) into the goal text via
     * [GoalPlanAgent.withConstraints] before calling [GoalPlanAgent.generate]. Writes nothing else
     * - see the tool's own declaration for why a plan is a proposal, not an action.
     *
     * [touchedReadThroughToolThisTurn]: a constraint is a member memory, so it takes the `memory`
     * tool's remember gate ([rememberBlockedByReadThroughTool], CLAUDE.md section 7). After mail or
     * a web page in the same turn it is refused, never saved: planted text must not become a
     * constraint. The plan is still generated, the same as for any other refused constraint.
     */
    private suspend fun generateGoalPlanTool(
        context: Context,
        args: JSONObject,
        touchedReadThroughToolThisTurn: Boolean = false,
    ): JSONObject {
        val goalText = args.optString("goal_text").trim()
        if (goalText.isBlank()) return result(false, "What's the goal?")

        // Persisted BEFORE the key check, deliberately: a stated fact ("no gym access") is real
        // regardless of whether a plan can be generated from it right now, and a missing/invalid
        // key must not cost the user having to say it again once the key is fixed.
        val newConstraint = args.optString("new_constraint").trim()
        // A refusal here is reported, never swallowed. The plan below is still generated, because
        // that is what the user asked for - but it is generated WITHOUT this constraint, since
        // goalPlanConstraints() reads them back out of the store the refused memory never reached.
        // So the model is told, as its own key, rather than being left to present a plan the user
        // will read as having honoured something it never saw.
        val constraints = goalPlanConstraints(context)
        val constraintRefusal = when {
            newConstraint.isBlank() -> null
            rememberBlockedByReadThroughTool(touchedReadThroughToolThisTurn) -> GOAL_CONSTRAINT_READ_THROUGH_REFUSAL
            else -> constraints.add(newConstraint)
        }

        val response = if (!GeminiKeyProvider.hasKey()) {
            result(false, "No Gemini key is set, so this cannot run. One can be added in Setup.")
        } else {
            val combinedGoalText = GoalPlanAgent.withConstraints(goalText, constraints.read())
            mapGoalPlanResult(GoalPlanAgent().generate(context, combinedGoalText))
        }
        return response.apply { constraintRefusal?.let { put("constraintNotSaved", it) } }
    }

    /**
     * Maps one [GoalPlanResult] to the tool-response JSON `generate_goal_plan` hands back to the
     * live model - the same split-for-testability shape [mapAdvisorResult] already uses for
     * `ask_advisor`, `internal` for the same reason. A [GoalPlanResult.Success] serialises every
     * present field of [GoalPlan] flat onto the result object (never nested under a generic
     * "plan" key) so the model can read `mealTarget`/`sleepTarget`/`workoutGoal`/`goals`/
     * `refusals` directly and hand the matching values straight to `set_meal_target`/
     * `set_sleep_target`/`accept_goal_plan`/`set_goal` once the user consents - no re-parsing of
     * prose on the way back in. Absent fields (refused, or never called for) are simply omitted,
     * matching [GoalPlan] itself.
     */
    internal fun mapGoalPlanResult(outcome: GoalPlanResult): JSONObject = when (outcome) {
        is GoalPlanResult.Success -> {
            KeyHealth.noteOk()
            val plan = outcome.plan
            result(true, plan.rationale).apply {
                plan.mealTarget?.let {
                    put(
                        "mealTarget",
                        JSONObject()
                            .put("caloriesKcal", it.caloriesKcal)
                            .put("proteinG", it.proteinG)
                            .put("carbsG", it.carbsG)
                            .put("fatG", it.fatG),
                    )
                }
                plan.sleepTarget?.let { put("sleepTarget", JSONObject().put("hours", it.hours)) }
                plan.pendingWorkoutGoal?.let { put("workoutGoal", it) }
                if (plan.goals.isNotEmpty()) {
                    put(
                        "goals",
                        JSONArray(
                            plan.goals.map { g ->
                                JSONObject().apply {
                                    put("aspect", g.aspect)
                                    put("statement", g.statement)
                                    g.targetValue?.let { put("targetValue", it) }
                                    g.unit?.let { put("unit", it) }
                                    g.metricKey?.let { put("metricKey", it) }
                                    g.deadline?.let { put("deadline", it) }
                                }
                            },
                        ),
                    )
                }
                if (plan.refusals.isNotEmpty()) put("refusals", JSONArray(plan.refusals))
            }
        }
        GoalPlanResult.RateLimited -> {
            KeyHealth.noteRateLimited()
            result(false, "The Gemini key hit its rate limit. Try again in a minute.")
        }
        GoalPlanResult.KeyInvalid -> {
            KeyHealth.noteInvalid()
            result(false, "The Gemini key was rejected. It can be checked in Setup.")
        }
        GoalPlanResult.Overloaded -> result(false, "The planner is overloaded. Try again shortly.")
        GoalPlanResult.Offline -> result(false, "No network connection. Try again once online.")
        GoalPlanResult.Failed -> result(false, "Could not put a plan together. Try again shortly.")
        is GoalPlanResult.ParseFailed -> {
            KeyHealth.noteOk()
            val prose = outcome.rawText.trim()
            if (prose.isEmpty()) {
                result(
                    true,
                    "The planner returned thoughts but no clean plan. Ask again for " +
                        "another pass.",
                )
            } else {
                result(
                    true,
                    prose + "\n\n(Say this as a rough idea only - I couldn't put a clean plan " +
                        "together from it, so there's nothing to accept yet.)",
                )
            }
        }
    }

    /**
     * `accept_goal_plan`. The far side of the one-consent split [GoalPlanAgent.generate]'s doc
     * comment describes: the user has now said one plain yes to the whole plan, so the workout
     * piece - the one write this recommender is allowed to make - may actually run, via
     * [GoalPlanAgent.accept]. Constructing a bare [GoalPlan] here rather than holding the one
     * `generate_goal_plan` returned is deliberate: [dispatch] is a stateless per-call function
     * with nothing to hold a plan across two tool calls, and [GoalPlanAgent.accept] itself reads
     * only [GoalPlan.pendingWorkoutGoal] - the model already has the exact `workoutGoal` string
     * from the generate call and is simply asked to hand it back, the same shape every other
     * multi-field write tool in this file already uses.
     *
     * A blank/omitted `workout_goal` is not an error - a nutrition-only plan is a complete plan,
     * not a broken one (see [GoalPlanAgent.accept]'s own doc comment).
     *
     * **CORRECTED, one-home ticket 05.** This used to be "the one place ticket 04's daily checklist
     * gets (re)built" via `GoalChecklistSync.materializeToday`, called from both branches below.
     * That mechanism retired with `goal-plans` ticket 04's own successor ruling (one-home ticket
     * 04: "the advisor writes a recurring checklists row"). **This flow no longer produces a
     * checklist on its own** - accepting a goal plan here still writes the meal/sleep/workout
     * targets it always did, but a checklist now comes ONLY from BIO's advisor proposing one
     * explicitly (`ask_advisor`/`accept_proposal`'s `create_checklist` op,
     * [com.kevin.legion.advisor.AdvisorProposalExecutor]) and the user separately accepting THAT
     * proposal - one-home ticket 05 rule 3, restated from the ticket verbatim: "accepting is
     * Kevin's step, not the model's... a proposal that writes on arrival is not a proposal." Under
     * the old mechanism a checklist appeared for free the moment targets existed, with no distinct
     * accept step of its own; that free side effect is what this correction removes; a checklist
     * is a decision from here on, not an automatic consequence of this one.
     */
    private suspend fun acceptGoalPlanTool(context: Context, args: JSONObject): JSONObject {
        val workoutGoal = args.optString("workout_goal").trim().takeIf { it.isNotBlank() }
        if (workoutGoal == null) {
            return result(true, "This plan had no workout piece to set up.")
        }

        val accepted = GoalPlanAgent().accept(
            context,
            GoalPlan(rationale = "", pendingWorkoutGoal = workoutGoal),
        )
        val message = accepted.workoutPlanMessage ?: "Could not put a workout plan together. Try again shortly."
        // **This comment used to read "WorkoutController.generatePlan (which accept() calls)
        // returns a plain message string either way, never a structured outcome - the same
        // 'I couldn't ...' prefix create_workout_plan's own dispatch already treats as its one
        // failure signature".** Both halves of that stopped being true: generatePlan returns a
        // WriteOutcome now, create_workout_plan's dispatch derives from it, and the prefix test had
        // already gone wrong before that - a REFUSED plan answers "That plan didn't go through:
        // ..." and starts with no such prefix, so the tool would have reported a plan the server
        // rejected as a success. The flag comes from the writer now (GoalPlan.workoutPlanSucceeded);
        // the null fallback only covers "accept() never ran", whose message is the one the prefix
        // test was actually written for.
        return result(success = accepted.workoutPlanSucceeded ?: false, message = message)
    }
}
