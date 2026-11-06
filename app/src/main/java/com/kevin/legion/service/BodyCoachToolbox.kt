package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.bodycoach.BodyCoachController
import com.kevin.legion.bodycoach.BodyCoachText
import com.kevin.legion.bodycoach.WorkoutSessionController
import com.kevin.legion.bodycoach.WorkoutText
import org.json.JSONArray
import org.json.JSONObject

/**
 * The fat-loss loop's READ tools (`.scratch/fat-loss/` ticket 10). All three are dispatched behind
 * `ask_body` ([LiveToolbox.DISPATCHED]), so they cost the Live setup message nothing, and all three
 * are reads, so ADR 0065's proof that `ask_body` reaches no write still holds.
 *
 * **The writes are not here, on purpose** (map decision 3): profile, waist, saved meals, the
 * proposal accept/dismiss and "stop the weekly weight line" are engine tools that `ask_engine`
 * reaches with `intent: "record"` (`server/engine_mcp/tools.py`), because Kevin ruled on 2026-08-18
 * that writes come out of the dispatchers and the payload ceiling has no room for new declarations.
 *
 * Every tool reads through [BodyCoachController], the controller the Body screen and the BIO digest
 * read (ADR 0035): the engine computes, the phone says the engine's words. Offline it says the saved
 * copy's age; with nothing saved it says nothing was estimated.
 */
object BodyCoachToolbox {

    /** Test seam: swapped for a controller over a fake backend. Production builds the real one. */
    internal var controllerFactory: (Context) -> BodyCoachController = BodyCoachController::forApp

    /** Test seam for the workout read (workouts, 2026-11-06). */
    internal var workoutFactory: (Context) -> WorkoutSessionController = WorkoutSessionController::forApp

    val NAMES = listOf("get_energy_estimate", "get_weight_trend", "list_saved_meals", "get_todays_workout")

    fun declarations(): JSONArray = JSONArray()
        .put(
            fn(
                name = "get_energy_estimate",
                description = "The user's maintenance calories, a suggested daily target for their chosen " +
                    "rate of loss, and a protein figure, computed by the engine from their profile and " +
                    "weight trend. Use for 'how much should I eat', 'what's my maintenance', 'how much " +
                    "protein', 'is my target right'. Every figure is an ESTIMATE: say so, and say its " +
                    "basis (formula, or their own trend). If inputs are missing, name them and ask for " +
                    "them; never guess a number. A pending proposal is only offered, never applied.",
            ),
        )
        .put(
            fn(
                name = "get_weight_trend",
                description = "The user's smoothed weight trend (not single readings), its rate per " +
                    "week, progress toward their weight goal, and their waist measurements. Use for " +
                    "'am I losing weight', 'how fast', 'how far to my goal'. An early-water flag means " +
                    "the first week's drop is mostly water: say so, never extrapolate it.",
            ),
        )
        .put(
            fn(
                name = "list_saved_meals",
                description = "The user's saved meals (e.g. 'usual breakfast') with estimated calories " +
                    "and protein. Logging one is log_meal with its name.",
            ),
        )
        .put(
            fn(
                name = "get_todays_workout",
                description = "Today's workout from the user's programme: each exercise as sets x reps " +
                    "@ weight, what is already ticked, why a weight went up or held, and sessions done " +
                    "this week. Use for 'what's my workout today', 'what weight for squats'. Every " +
                    "number comes from the programme's rules: say it as given, never invent or change " +
                    "one. 'Your pick' means a calibration session: a weight they could lift for 2 more " +
                    "reps than the top of the range.",
            ),
        )

    // `name = "..."` written out at each call, not via NAMES: tools/voice_guide.py and
    // VoiceGuideDataTest find tool names by scanning for that exact shape.
    private fun fn(name: String, description: String): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()),
            )

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String): JSONObject? {
        if (name !in NAMES) return null
        if (name == "get_todays_workout") return WorkoutText.todayResult(workoutFactory(context).today())
        val controller = controllerFactory(context)
        return when (name) {
            "get_energy_estimate" -> BodyCoachText.energyResult(controller.energy())
            "get_weight_trend" -> BodyCoachText.trendResult(controller.trend())
            else -> BodyCoachText.savedMealsResult(controller.savedMeals())
        }
    }
}
