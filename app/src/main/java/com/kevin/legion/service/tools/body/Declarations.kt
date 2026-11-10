@file:Suppress("MaxLineLength")

package com.kevin.legion.service.tools.body

import com.kevin.legion.service.tools.fn
import com.kevin.legion.service.tools.obj
import com.kevin.legion.service.tools.schema
import org.json.JSONObject

/**
 * The body tools' Live declarations, split out of `LiveToolbox.allDeclarations()`
 * (architecture review 2026-11-10, item 8). Moved verbatim; the order the setup message
 * carries them in is [com.kevin.legion.service.tools.DECLARATION_ORDER], not this file's.
 * Dispatch is still `LiveToolbox.dispatch`.
 */
// Moved verbatim from LiveToolbox.allDeclarations(), where the detekt baseline already carried
// these long description lines and the one long function: same text, so the same exemption.
@Suppress("LongMethod", "MaxLineLength")
internal object Declarations {
    fun declarations(): List<JSONObject> = buildList {
        // --- Workouts (ticket 08 D20-D24) ------------------------------------

        add(fn(
            name = "log_workout_set",
            description = "Log a set-group just performed, e.g. 'bench 135 for 8' or 'three sets of " +
                "squats at 225'; it ticks today's workout when the exercise is in it. Use " +
                "the moment the user reports a set - no confirmation needed, just log it and say " +
                "back what was recorded. If the user only names the exercise with no set count, " +
                "ask once for how many sets rather than guessing.",
            params = obj(
                "exercise" to schema("string", "The exercise, e.g. squats, bench press, pushups."),
                "sets" to schema("integer", "How many sets, e.g. 3."),
                "reps" to schema("integer", "Reps per set, if stated."),
                "weight" to schema("number", "Weight used, if stated."),
                "weight_unit" to schema("string", "Unit for weight, if stated.", enum = listOf("lbs", "kg")),
            ),
            required = listOf("exercise", "sets"),
        ))

        add(fn(
            name = "log_bodyweight",
            description = "Log the user's current bodyweight, e.g. 'I weigh 180 today'. A plain " +
                "reported measurement, separate from any workout log. Not a waist size.",
            params = obj(
                "weight" to schema("number", "The bodyweight value."),
                "weight_unit" to schema("string", "Unit for the weight.", enum = listOf("lbs", "kg")),
            ),
            required = listOf("weight", "weight_unit"),
        ))

        add(fn(
            name = "get_workout_gap",
            description = "Report sessions done versus sessions planned this week - the user's " +
                "adherence to their current plan. Use when the user asks how they're doing this " +
                "week or how they're sticking to their plan. Says there's no plan yet if none is set.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "list_recent_workouts",
            description = "Read back recently logged workout sets.",
            params = obj("count" to schema("integer", "How many to return. Default 10.")),
            required = listOf(),
        ))

        // --- Meals (ticket 09 D25-D28) ----------------------------------------

        add(fn(
            name = "log_meal",
            description = "Log a meal from the user's spoken description, e.g. 'I had a chicken " +
                "burrito bowl for lunch'. Calories and macros are LLM ESTIMATES from the description, " +
                "never measured - always phrase them as estimates, never as fact.",
            params = obj("description" to schema("string", "What was eaten, in the user's own words.")),
            required = listOf("description"),
        ))

        add(fn(
            name = "set_meal_target",
            // Coach voice fixes (2026-11-06): "lock it in" reached this with the estimate's figure.
            // Only a number the user says as his own; kcal and protein only (Kevin's ruling), so
            // carbs and fat are not offered. CoachRouting.setMealTargetRefusal backs the wording.
            description = "Set a calorie or protein target the user SAYS as their own number, e.g. " +
                "'make it 2200 calories'. Never to lock or accept a plan: 'lock it in' is " +
                "ask_engine (lock_body_plan).",
            params = obj(
                "calories" to schema("integer", "Daily calorie target."),
                "protein_g" to schema("number", "Daily protein target in grams."),
            ),
            required = listOf(),
        ))

        add(fn(
            name = "get_meal_gap",
            description = "Report today's logged calories/macros against the daily target. If " +
                "nothing has been logged today, says so explicitly - NEVER report today as zero " +
                "calories, that would be a lie, not a fact.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "list_recent_meals",
            description = "Read back recently logged meals, newest first, each with the date it " +
                "was logged (yyyy-MM-dd and weekday, the phone's own day). Calorie/macro figures " +
                "are ESTIMATES, always phrase them as estimates, never as fact.",
            params = obj("count" to schema("integer", "How many to return. Default 10.")),
            required = listOf(),
        ))

        // --- Sleep (Kevin, 2026-08-07: "i want to be able to log sleep too") -----
        //
        // Modelled on meals/workouts (ticket 08/09) - REPORTED tier, no
        // reconciliation gate (CLAUDE.md §4's gate only applies where a source
        // document states its own anchor; nothing external verifies sleep).

        add(fn(
            name = "log_sleep",
            description = "Log a night's sleep, e.g. 'I slept 7 and a half hours' or 'log 8 hours, " +
                "quality 4 out of 5'. This is the user's OWN REPORT - nothing verifies it, so " +
                "never present a sleep figure as independently confirmed.",
            params = obj(
                "duration_hours" to schema("number", "How long they slept, in hours - decimals allowed, e.g. 7.5."),
                "quality" to schema("integer", "Optional self-rated sleep quality, 1 (worst) to 5 (best)."),
                "date" to schema("string", "MM/DD/YYYY, the morning they woke up. Defaults to today if the user doesn't say."),
                "notes" to schema("string", "Any extra detail the user gives, e.g. 'woke up twice'."),
            ),
            required = listOf("duration_hours"),
        ))

        add(fn(
            name = "set_sleep_target",
            description = "Set the user's nightly sleep target, e.g. 'I want to get 8 hours of " +
                "sleep a night'. Applies from tonight onward until changed.",
            params = obj("hours" to schema("number", "The target hours of sleep per night, e.g. 8.")),
            required = listOf("hours"),
        ))

        add(fn(
            name = "get_sleep_gap",
            description = "Report last night's logged sleep against the nightly target. If nothing " +
                "has been logged for last night, says so explicitly - NEVER report it as zero hours, " +
                "that would be a lie, not a fact.",
            params = obj(),
            required = listOf(),
        ))

        add(fn(
            name = "list_recent_sleep",
            description = "Read back recently logged nights of sleep.",
            params = obj("count" to schema("integer", "How many nights to return. Default 10.")),
            required = listOf(),
        ))

        // --- Shared undo (ticket 11 D36; extended to sleep 2026-08-07) ---------

        add(fn(
            name = "undo_last_log",
            description = "Undo the single most recently logged item across workouts, bodyweight, " +
                "meals, and sleep - whichever was logged last. Use when the user says something " +
                "like 'no, undo that' or 'that's wrong, take it back' right after logging something " +
                "in one of these domains. Never used for ledger money - that's clear_pending_transaction.",
            params = obj(),
            required = listOf(),
        ))
    }
}
