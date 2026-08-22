package com.kevin.legion.advisor

import android.content.Context
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.StructuredOutputRequest
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.workouts.WorkoutController
import org.json.JSONArray
import org.json.JSONObject

/**
 * One meal-target line a [GoalPlan] proposes, shaped to match `set_meal_target`'s own params
 * field-for-field (see `service/LiveToolbox.kt`'s declaration of that tool) - a caller hands these
 * straight through, once the user accepts, with no translation.
 */
data class GoalPlanMealTarget(val caloriesKcal: Int, val proteinG: Double, val carbsG: Double, val fatG: Double)

/** A nightly sleep target, shaped to match `set_sleep_target`'s own single `hours` param. */
data class GoalPlanSleepTarget(val hours: Double)

/**
 * One long-term goal line, shaped to match `set_goal`'s own params exactly - see
 * `service/LiveToolbox.kt`'s `setGoalTool`. [aspect] is always `"bio"` - the recommender is
 * BIO-only for now (Kevin, 2026-08-21: "do not generalise the recommender across cred/log/fleet");
 * the field still carries the string rather than being dropped so [GoalPlanGoal] hands `set_goal`
 * exactly the shape it already expects, unchanged the day this does generalise. [deadline] stays
 * the raw `MM/dd/yyyy` string that tool itself parses (`PENDING_DATE_FORMAT`), never converted to
 * an epoch here - doing that conversion twice is two copies of the same date parsing to keep in
 * sync, and the tool already owns it.
 */
data class GoalPlanGoal(
    val aspect: String,
    val statement: String,
    val targetValue: Double? = null,
    val unit: String? = null,
    val metricKey: String? = null,
    val deadline: String? = null,
)

/**
 * One generated plan (ticket 02, `goal-plans`; scope loosened and re-shaped by Kevin, 2026-08-21 -
 * see [GoalPlanAgent]'s class doc for the full set of overrides). [rationale] is the spoken
 * framing, and it is the ONE place the "this is a starting point" honesty clause lives - repeating
 * a hedge on every field trains the user to stop hearing it (settled decision 5), and the hedge
 * itself must never promise an adjustment the app does not perform ("worth revisiting", never "OK
 * to be adjusted, later"; CLAUDE.md §7's outcome-verb rule applied to a promise instead of a
 * claim).
 *
 * [mealTarget], [sleepTarget], and [goals] are exactly the arguments a caller hands to
 * `set_meal_target`, `set_sleep_target`, and `set_goal` once the user gives ONE consent to the
 * whole plan (settled decision 14) - [GoalPlanAgent] itself never calls any of those three or
 * writes a Room row for them. **The workout piece is the one exception**, by Kevin's explicit
 * design call: [GoalPlanAgent.generate] calls `create_workout_plan`'s own backing function
 * ([WorkoutController.generatePlan]) directly, the same "recommender calls the existing tool
 * rather than programming its own exercise table" posture that tool already has for direct voice
 * dictation. [workoutPlanMessage] is that call's own return value - already true, not a proposal -
 * so a caller renders it as a fact, never re-asks for consent on it.
 *
 * [refusals] names, in plain words, every target the model or the code declined to fill in and
 * why - settled decisions 9/10: a target that crosses a boundary is refused ON ITS OWN, the rest
 * of the plan still generates, and the refusal is never silent.
 */
data class GoalPlan(
    val rationale: String,
    val mealTarget: GoalPlanMealTarget? = null,
    val sleepTarget: GoalPlanSleepTarget? = null,
    val workoutPlanMessage: String? = null,
    val goals: List<GoalPlanGoal> = emptyList(),
    val refusals: List<String> = emptyList(),
    /**
     * The workout goal sentence [GoalPlanAgent.parse] read off the model's response - a PROPOSAL,
     * still awaiting the [WorkoutController.generatePlan] call that [GoalPlanAgent.accept] makes
     * once the user has accepted the plan. A freshly generated plan carries this and no
     * [workoutPlanMessage]; an accepted one carries the message and no pending sentence. The two
     * are never populated together, and which one is set is exactly how a reader tells a proposed
     * workout piece from a written one.
     */
    internal val pendingWorkoutGoal: String? = null,
)

/**
 * Typed outcome of one [GoalPlanAgent.generate] call - the same [AgentResult]-shaped vocabulary
 * [AdvisorResult] already gives [AdvisorAgent]'s caller, plus [ParseFailed] for a 200 OK response
 * whose text did not parse into [GoalPlan]'s required shape. See [AdvisorResult.ParseFailed]'s doc
 * comment for why [rawText] is worth keeping even on a parse failure - the same reasoning applies
 * here unchanged.
 */
sealed class GoalPlanResult {
    data class Success(val plan: GoalPlan) : GoalPlanResult()
    object RateLimited : GoalPlanResult()
    object KeyInvalid : GoalPlanResult()
    object Overloaded : GoalPlanResult()
    object Offline : GoalPlanResult()
    object Failed : GoalPlanResult()
    data class ParseFailed(val rawText: String) : GoalPlanResult()
}

/**
 * The recommender: turns a prose BIO goal ("lose fat, gain muscle", "I only have kettlebells")
 * into a [GoalPlan] (ticket 02, `goal-plans`). One-shot [SubAgent.askTyped], the same harness
 * shape [AdvisorAgent] uses and for the same reason - there is nothing here for a tool-calling
 * [SubAgent.investigate] loop to pull; the primed doctrine already hands over everything the model
 * needs to reason from in one POST.
 *
 * **Kevin's 2026-08-21 overrides to this ticket's original brief, binding on this file:**
 * - **Accuracy is deliberately loose.** "just a check list of recommended workouts to loosely
 *   follow etc" - no calibration machinery, no per-individual accuracy chasing, no field added to
 *   make a number more defensible. Simpler wins over more accurate wherever they trade off.
 * - **Doctrine is split, not duplicated.** [PrimingTopic.BIO] stays the sole authority on
 *   numbers; [PrimingTopic.PLAN] owns only the shape of turning a goal into targets. [generate]
 *   primes BOTH, concatenated, via [Priming.combinedText] - never [PrimingTopic.PLAN] alone.
 * - **`create_workout_plan` is CALLED, not reprogrammed.** [generate] invokes
 *   [WorkoutController.generatePlan] directly when the model proposes a workout goal sentence,
 *   rather than [GoalPlanAgent] building its own exercise/sets table. This is the one target that
 *   is already written by the time [generate] returns - see [GoalPlan]'s doc comment.
 * - **Protein is always against total bodyweight.** No body-fat input anywhere in this path -
 *   never requested, never estimated, never guessed from anything else in the goal text.
 * - **A boundary refuses its OWN target, never the whole plan.** See [parse]'s doc comment for
 *   the one boundary ([HARD_FLOOR_CALORIES_KCAL]) enforced in Kotlin rather than left to prose.
 * - **BIO-only.** `goals` may only carry `aspect = "bio"` for now - generalising to cred/log/fleet
 *   is explicitly deferred.
 */
class GoalPlanAgent(
    /**
     * Builds the [SubAgent] for one exchange, given the fully-composed `systemInstruction`. A
     * factory rather than a fixed instance for the same reason [AdvisorAgent]'s constructor is:
     * so a unit test can substitute a fake without a network call. The default constructs a real
     * network-backed [SubAgent] with search OFF - a plan is generated from the playbook doctrine
     * handed to it, never from a live web lookup (CLAUDE.md §2 decision 4: "research happens
     * once, offline, and becomes the shipped playbook text").
     */
    private val subAgentFactory: (systemInstruction: String) -> SubAgent =
        { systemInstruction -> SubAgent(systemInstruction = systemInstruction, useSearch = false) },
) {
    /**
     * Generates one plan for [goalText] (the user's own words). Reads [PrimingTopic.BIO] and
     * [PrimingTopic.PLAN] together via [Priming.combinedText], composes that into the model's
     * context, and asks for [responseSchema]'s shape.
     *
     * Returns [GoalPlanResult.ParseFailed] with the raw text rather than a half-formed [GoalPlan]
     * when the response does not parse - "a plan that will not parse must be a clean failure,
     * never a partial accept" (ticket 02). Returns [GoalPlanResult.Failed] when the two playbooks
     * combined exceed [PrimingTopic.MAX_CHARS] - see [Priming.combinedText]'s doc comment for why
     * that is a build-time problem to surface, never a runtime trim.
     *
     * **This method WRITES NOTHING.** Every target it returns is a proposal, including the
     * workout piece, which comes back as [GoalPlan.pendingWorkoutGoal] rather than an already-run
     * [WorkoutController.generatePlan] call. Applying the plan is [accept]'s job, and the split
     * exists because settled decision 14 makes acceptance ONE consent over the whole plan: a
     * generate that quietly wrote the workout plan first would mean asking to SEE a plan already
     * committed part of it, and a plan the user then rejected would leave a real
     * [com.kevin.legion.data.local.WorkoutPlan] row behind with nothing pointing at it.
     *
     * That is also CLAUDE.md §7's outcome-verb rule read from the other end. The assistant may
     * only say it did something after a tool actually ran; the mirror of that is that nothing
     * should run before the user has said to do it.
     */
    suspend fun generate(context: Context, goalText: String): GoalPlanResult {
        val combinedDoctrine = Priming.combinedText(context, listOf(PrimingTopic.BIO, PrimingTopic.PLAN))
            ?: return GoalPlanResult.Failed
        val promptContext = composeContext(combinedDoctrine)

        val outcome = when (val result = subAgentFactory(SYSTEM_INSTRUCTION).askTyped(
            context = promptContext,
            question = goalText,
            structuredOutput = StructuredOutputRequest(responseSchema()),
        )) {
            is AgentResult.Success -> parse(result.text)?.let { GoalPlanResult.Success(it) }
                ?: GoalPlanResult.ParseFailed(result.text)
            AgentResult.RateLimited -> GoalPlanResult.RateLimited
            AgentResult.KeyInvalid -> GoalPlanResult.KeyInvalid
            AgentResult.Overloaded -> GoalPlanResult.Overloaded
            AgentResult.Offline -> GoalPlanResult.Offline
            AgentResult.Failed -> GoalPlanResult.Failed
        }

        return outcome
    }

    /**
     * Applies an accepted [plan]'s workout piece, and nothing else.
     *
     * This is the far side of the one-consent split described on [generate]: the user has now said
     * yes to the whole plan, so the single write this recommender is allowed to make may run.
     * [WorkoutController.generatePlan] is called directly - the existing tool, not a second
     * programmer (Kevin, 2026-08-21: "The recommender CALLS it and stays out of programming") -
     * and its own return message is carried into [GoalPlan.workoutPlanMessage] as an already-true
     * fact, replacing the proposal in [GoalPlan.pendingWorkoutGoal].
     *
     * **The other three targets are deliberately not written here.** [GoalPlan.mealTarget],
     * [GoalPlan.sleepTarget] and [GoalPlan.goals] are applied by the caller through
     * `set_meal_target`, `set_sleep_target` and `set_goal` - the tools that already own those
     * writes and are already in the advisor's writable-op allowlist. Settled decision 9 is
     * explicit that nothing new gets write access, and routing them through here would quietly
     * make this class a fourth writer.
     *
     * A plan with no workout piece is returned unchanged rather than treated as an error: a goal
     * that is purely nutritional is a legitimate plan, not a failed one.
     */
    suspend fun accept(context: Context, plan: GoalPlan): GoalPlan {
        val workoutGoalSentence = plan.pendingWorkoutGoal ?: return plan
        val message = WorkoutController.generatePlan(context, workoutGoalSentence)
        return plan.copy(workoutPlanMessage = message, pendingWorkoutGoal = null)
    }

    companion object {
        /**
         * A calorie target this recommender may never propose, enforced in Kotlin rather than
         * left to [PlanPlaybook]'s prose (settled decision 11). `.scratch/goal-plans/research/
         * 01-doctrine.md` §1: "<=800 kcal/day is a medically supervised intervention, not a
         * self-directed target" (NIH National Task Force, JAMA 1993). A playbook edit can still
         * keep every [PrimingTopic.PLAN] `requiredPhrases` substring while rewording this
         * paragraph into something that reads fine and no longer refuses the number - a substring
         * check cannot catch a rewording that keeps the words and drops the meaning, which is
         * exactly why this one boundary is a number in code, not prose the model or a driver's
         * edit could route around.
         */
        const val HARD_FLOOR_CALORIES_KCAL = 800

        /**
         * The `systemInstruction` for every [generate] call: the task itself, the honesty rule
         * that binds [GoalPlan.rationale] (settled decision 5/8 - invite, never commit), the
         * refusal contract (settled decisions 9/10), and [RESPONSE_SCHEMA]'s prose contract as
         * belt alongside [responseSchema]'s machine-enforced braces - the same two-layer posture
         * [AdvisorAnswer.RESPONSE_SCHEMA]'s doc comment explains for why the prose copy is not
         * simply deleted once a real schema exists.
         */
        private val TASK_AND_RULES: String = """
            You are a recommender that turns a person's own words about a BIO goal into a loose,
            followable plan - a daily calorie/macro target, a nightly sleep target, a workout goal
            sentence, and one or more long-term goals. This does not need to be precise: a rough,
            loosely-followable plan is the whole deliverable, never a calibrated prescription.

            You are handed two playbooks above the goal itself, labelled BIO and PLAN. BIO is the
            authority on every number - protein, calories, volume. PLAN is the authority on how to
            turn a goal into this plan's shape, how to hedge, and when to refuse. Reason from both,
            never from outside knowledge, and never contradict a boundary either one names.

            Never ask for or estimate body fat, anywhere in this process - protein and calorie
            targets are always against total bodyweight.

            HONESTY (binding): every number you propose is a starting point, not a measurement.
            Say so exactly ONCE, inside "rationale", in your own words close to: "Starting at
            2,300 calories and 180g protein - worth revisiting once you have a couple of weeks of
            weight data." Never phrase the hedge as a promise the app itself will keep ("I'll
            adjust this in two weeks") - it is an invitation for the person to come back, not a
            commitment this app performs on its own. Do not repeat any hedge on any other field -
            state every other number plainly.

            REFUSAL (binding): if a target would cross a boundary named in either playbook, refuse
            that target ONLY, omitting its field. Generate every other field normally, and add one
            plain-English entry to "refusals" naming which target and why (e.g. "sleep target: the
            goal describes a diagnosed condition affecting sleep, which needs a physician, not this
            app").

            Never fail the whole plan over one refused field, and never silently drop a field
            without a matching "refusals" entry.

            Only fill in the fields the goal actually calls for - a goal about sleep alone should
            not invent a workout target, and a goal with no clear number should still produce a
            goal line with no target_value rather than inventing one. "goals" entries must use
            aspect "bio" only. Never omit every field: a plan that proposes and refuses nothing at
            all is not a plan.
        """.trimIndent()

        /**
         * [TASK_AND_RULES] plus [RESPONSE_SCHEMA]'s prose contract - kept as a two-step
         * concatenation rather than interpolating `$RESPONSE_SCHEMA` directly inside
         * [TASK_AND_RULES]'s own `trimIndent()` block, because [RESPONSE_SCHEMA]'s lines start at
         * column zero and would collapse `trimIndent()`'s computed common indent for the whole
         * block to zero, leaving every other line's source-level leading spaces in the actual
         * prompt text. Two `trimIndent()`-clean blocks joined after the fact avoids that entirely.
         */
        val SYSTEM_INSTRUCTION: String = TASK_AND_RULES + "\n\n" + RESPONSE_SCHEMA.trim()

        /** Prose copy of [responseSchema]'s shape - see [SYSTEM_INSTRUCTION]'s doc comment. */
        const val RESPONSE_SCHEMA = """
Respond with ONLY one JSON object, no prose outside it, no markdown code fence, shaped exactly:
{
  "rationale": "<short spoken framing, the ONE place the starting-point honesty line goes>",
  "mealTarget": {"caloriesKcal": <integer>, "proteinG": <number>, "carbsG": <number>, "fatG": <number>},
  "sleepTarget": {"hours": <number>},
  "workoutGoal": "<a goal sentence to hand to the workout planner, or omit if none>",
  "goals": [
    {"aspect": "bio", "statement": "<the goal in plain words>", "targetValue": <number, optional>, "unit": "<optional>", "metricKey": "<optional>", "deadline": "<optional, MM/dd/yyyy>"}
  ],
  "refusals": ["<plain-English: which target was refused and why>"]
}
Omit "mealTarget", "sleepTarget", or "workoutGoal" entirely (not null) when the goal does not call
for one, or when it is refused - in the refused case, add the matching entry to "refusals" instead.
"goals" and "refusals" may be empty arrays, but at least one of
mealTarget/sleepTarget/workoutGoal/goals/refusals must be present.
"""

        /**
         * The `context` block for one exchange: [combinedDoctrine] (already both playbooks,
         * concatenated by [Priming.combinedText]) under a single labelled header, matching
         * [AdvisorAgent.composeContext]'s "PLAYBOOK:" convention so a reader scanning both files
         * recognises the same shape. `internal` (not private) so a unit test can inspect the
         * composed block without a Context or a network call, the same pattern
         * [AdvisorAgent.composeContext] already uses.
         */
        internal fun composeContext(combinedDoctrine: String): String =
            "PLAYBOOK:\n" + combinedDoctrine.trim()

        /**
         * [RESPONSE_SCHEMA]'s prose contract translated into the Gemini `responseSchema` object -
         * see [StructuredOutputRequest]'s doc comment for the accepted OpenAPI-3.0-subset field
         * list, checked against it deliberately before writing this rather than guessed. Returns a
         * fresh [JSONObject] on every call, matching [AdvisorAnswer.responseSchema]'s same
         * "mutable object, never a shared instance" posture.
         *
         * Only "rationale" is `required` - every other top-level field mirrors [RESPONSE_SCHEMA]'s
         * prose "omit what the goal does not call for" instruction, and [parse] enforces the "at
         * least one of the five" rule structurally, since the schema language here has no clean
         * way to express "at least one of these properties is present". `goals[].aspect` is
         * pinned to the single value `"bio"` - settled decision 12, BIO-only for now.
         */
        fun responseSchema(): JSONObject = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("rationale", JSONObject().apply {
                    put("type", "STRING")
                    put("description", "Short spoken framing; the one place the starting-point honesty line goes.")
                })
                put("mealTarget", JSONObject().apply {
                    put("type", "OBJECT")
                    put("nullable", true)
                    put("properties", JSONObject().apply {
                        put("caloriesKcal", JSONObject().put("type", "INTEGER"))
                        put("proteinG", JSONObject().put("type", "NUMBER"))
                        put("carbsG", JSONObject().put("type", "NUMBER"))
                        put("fatG", JSONObject().put("type", "NUMBER"))
                    })
                    put("required", JSONArray().put("caloriesKcal").put("proteinG").put("carbsG").put("fatG"))
                    put("propertyOrdering", JSONArray().put("caloriesKcal").put("proteinG").put("carbsG").put("fatG"))
                })
                put("sleepTarget", JSONObject().apply {
                    put("type", "OBJECT")
                    put("nullable", true)
                    put("properties", JSONObject().apply {
                        put("hours", JSONObject().put("type", "NUMBER"))
                    })
                    put("required", JSONArray().put("hours"))
                    put("propertyOrdering", JSONArray().put("hours"))
                })
                put("workoutGoal", JSONObject().apply {
                    put("type", "STRING")
                    put("nullable", true)
                    put("description", "A goal sentence handed to create_workout_plan's own backing generator.")
                })
                put("goals", JSONObject().apply {
                    put("type", "ARRAY")
                    put("items", JSONObject().apply {
                        put("type", "OBJECT")
                        put("properties", JSONObject().apply {
                            put("aspect", JSONObject().apply {
                                put("type", "STRING")
                                put("enum", JSONArray().put("bio"))
                            })
                            put("statement", JSONObject().put("type", "STRING"))
                            put("targetValue", JSONObject().apply {
                                put("type", "NUMBER")
                                put("nullable", true)
                            })
                            put("unit", JSONObject().apply {
                                put("type", "STRING")
                                put("nullable", true)
                            })
                            put("metricKey", JSONObject().apply {
                                put("type", "STRING")
                                put("nullable", true)
                            })
                            put("deadline", JSONObject().apply {
                                put("type", "STRING")
                                put("nullable", true)
                            })
                        })
                        put("required", JSONArray().put("aspect").put("statement"))
                        put("propertyOrdering", JSONArray().put("aspect").put("statement").put("targetValue").put("unit").put("metricKey").put("deadline"))
                    })
                })
                put("refusals", JSONObject().apply {
                    put("type", "ARRAY")
                    put("description", "Plain-English: which target was refused and why, one entry per refused target.")
                    put("items", JSONObject().put("type", "STRING"))
                })
            })
            put("required", JSONArray().put("rationale"))
            put("propertyOrdering", JSONArray().put("rationale").put("mealTarget").put("sleepTarget").put("workoutGoal").put("goals").put("refusals"))
        }

        /**
         * Parses one plan out of the model's raw response text. Tolerates a ```json ... ``` or
         * ``` ... ``` fence, the same as [AdvisorAnswer.parse] - reuses that method's stripping
         * rather than a second copy of the same defensive logic. Returns null (a clean failure,
         * never a partial accept) when:
         * - "rationale" is missing or blank,
         * - any present sub-object is missing one of ITS required fields (a `mealTarget` with no
         *   `proteinG` is not silently dropped to null - the model was asked for all four together
         *   or none, and a half-filled target is worse than no target),
         * - a `goals` entry is missing `aspect` or `statement`, or names an aspect other than
         *   `"bio"` (settled decision 12),
         * - or the response fills in NONE of mealTarget/sleepTarget/workoutGoal/goals/refusals - a
         *   plan that proposes and refuses nothing at all is not a valid plan, structurally, not
         *   just by convention.
         *
         * **The one boundary this function enforces itself, unconditionally, regardless of what
         * the model returned:** a `mealTarget` whose `caloriesKcal` is at or below
         * [HARD_FLOOR_CALORIES_KCAL] is dropped, and a refusal note explaining why is appended to
         * [GoalPlan.refusals] - even if the model did not flag it. See [HARD_FLOOR_CALORIES_KCAL]'s
         * own doc comment for why this one guard lives here instead of only in [PlanPlaybook]'s
         * prose.
         *
         * A parsed `workoutGoal` is carried in [GoalPlan.pendingWorkoutGoal] and NOT yet in
         * [GoalPlan.workoutPlanMessage] - this function is pure (no [Context], no network) and
         * cannot itself call [WorkoutController.generatePlan]; [generate] performs that call and
         * moves the result across afterward. A caller using [parse] directly (as the unit tests
         * do) sees the proposed sentence, not an executed plan - documented here so that
         * distinction is never assumed to be a network call this function secretly makes.
         */
        fun parse(raw: String): GoalPlan? {
            val stripped = AdvisorAnswer.stripFence(raw)
            return try {
                val obj = JSONObject(stripped)
                val rationale = obj.optString("rationale").takeIf { it.isNotBlank() } ?: return null

                var mealTarget = if (obj.has("mealTarget") && !obj.isNull("mealTarget")) {
                    val m = obj.getJSONObject("mealTarget")
                    if (!m.has("caloriesKcal") || !m.has("proteinG") || !m.has("carbsG") || !m.has("fatG")) return null
                    GoalPlanMealTarget(
                        caloriesKcal = m.getInt("caloriesKcal"),
                        proteinG = m.getDouble("proteinG"),
                        carbsG = m.getDouble("carbsG"),
                        fatG = m.getDouble("fatG"),
                    )
                } else null

                val refusals = mutableListOf<String>()
                val refusalsJson = obj.optJSONArray("refusals")
                if (refusalsJson != null) {
                    for (i in 0 until refusalsJson.length()) refusals.add(refusalsJson.optString(i))
                }

                // The one boundary enforced here in Kotlin, unconditionally - see
                // HARD_FLOOR_CALORIES_KCAL's doc comment.
                if (mealTarget != null && mealTarget.caloriesKcal <= HARD_FLOOR_CALORIES_KCAL) {
                    refusals.add(
                        "meal target: ${mealTarget.caloriesKcal} kcal/day is at or below " +
                            "$HARD_FLOOR_CALORIES_KCAL kcal/day, a medically supervised " +
                            "intervention this app cannot propose on its own.",
                    )
                    mealTarget = null
                }

                val sleepTarget = if (obj.has("sleepTarget") && !obj.isNull("sleepTarget")) {
                    val s = obj.getJSONObject("sleepTarget")
                    if (!s.has("hours")) return null
                    GoalPlanSleepTarget(hours = s.getDouble("hours"))
                } else null

                val workoutGoal = if (obj.has("workoutGoal") && !obj.isNull("workoutGoal")) {
                    obj.optString("workoutGoal").takeIf { it.isNotBlank() }
                } else null

                val goalsJson = obj.optJSONArray("goals") ?: JSONArray()
                val goals = mutableListOf<GoalPlanGoal>()
                for (i in 0 until goalsJson.length()) {
                    val g = goalsJson.optJSONObject(i) ?: return null
                    val aspect = g.optString("aspect").trim().lowercase()
                    if (aspect != "bio") return null
                    val statement = g.optString("statement").trim()
                    if (statement.isBlank()) return null
                    goals.add(
                        GoalPlanGoal(
                            aspect = aspect,
                            statement = statement,
                            targetValue = if (g.has("targetValue") && !g.isNull("targetValue")) g.optDouble("targetValue") else null,
                            unit = g.optString("unit").trim().takeIf { it.isNotBlank() },
                            metricKey = g.optString("metricKey").trim().takeIf { it.isNotBlank() },
                            deadline = g.optString("deadline").trim().takeIf { it.isNotBlank() },
                        ),
                    )
                }

                if (mealTarget == null && sleepTarget == null && workoutGoal == null &&
                    goals.isEmpty() && refusals.isEmpty()
                ) {
                    return null
                }

                GoalPlan(
                    rationale = rationale,
                    mealTarget = mealTarget,
                    sleepTarget = sleepTarget,
                    pendingWorkoutGoal = workoutGoal,
                    goals = goals,
                    refusals = refusals,
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
