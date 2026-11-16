package com.kevin.legion.service.testturn

import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.tools.LiveSurface
import org.json.JSONObject

/**
 * What a test turn may run (Kevin, 2026-11-15: test the voice from a computer, on his real data).
 * **Deny by default:** a tool runs in a test conversation only if it is listed here as a READ, by
 * its resolved name (after [LiveSurface.resolve]) and, for a tool with an `action`, by that action.
 * Everything else (a write, a call, a message, a screen, a control, anything new) is refused in
 * words with `test_mode: true`, unless the test explicitly allowed writes for that case.
 *
 * `TestTurnPolicyTest` fails on any reachable tool name that is in neither [READS] nor
 * [REFUSED], so a new tool forces a decision here instead of slipping through.
 */
object TestTurnPolicy {
    /** Read-only whatever the arguments. A router (`ask_*`) is a read unless called with intent=record. */
    val READS: Set<String> = setOf(
        "get_current_time", "read_calendar", "read_list", "get_last_ticked", "get_sitrep",
        "track_package", "flight_status", "trip_status", "get_current_location", "area_info",
        "get_reported_crime_history", "list_voice_notes", "read_voice_note", "list_budget_categories",
        "list_goals", "get_music_queue", "browse_my_music", "list_aspects", "describe_aspect",
        "query_records", "end_conversation",
        // Routers and what sits behind them (DISPATCHED is reads only).
        "ask_fleet", "ask_body", "ask_goals", "ask_pantry", "ask_mail", "ask_money", "ask_area",
        "read_vehicle_sensor", "get_vehicle_data", "get_codes", "diagnose_codes", "get_code_history",
        "check_readiness", "check_cold_start", "get_mpg", "get_specs", "lookup_vin", "check_recalls",
        "list_vehicles", "get_next_service", "ask_maintenance", "triage_symptom", "list_build_history",
        "get_trend", "get_health", "get_spend", "list_recent_meals", "get_meal_gap", "list_recent_sleep",
        "get_sleep_gap", "list_recent_workouts", "get_workout_gap", "get_energy_estimate",
        "get_weight_trend", "list_saved_meals", "get_todays_workout", "get_workout_programme",
        "suggest_meal", "get_exercise_alternatives", "ask_advisor", "list_recent_groceries",
        "get_grocery_spend", "search_mail", "read_mail", "get_balance", "get_monthly_spend",
        "list_recent_transactions", "list_pending_transactions", "get_reported_crime_history",
    )

    /** A tool that reads for these `action` values and writes or acts for every other. */
    val READ_ACTIONS: Map<String, Set<String>> = mapOf(
        "manage_checklist" to setOf("read", "lists", "ticked"),
        "watch" to setOf("list"),
        "memory" to setOf("recall", "list"),
        "books" to setOf("status"),
        // "check" may start a download: refused.
        "app_update" to setOf("whats_new"),
        "bought_log" to setOf("last", "recent"),
        "clock" to setOf("alarm_list", "timer_left"),
    )

    /**
     * Refused in test mode: writes, calls, mail, controls, screens. Listed only so the test can
     * tell a deliberate refusal from a tool nobody classified; anything missing is refused too.
     */
    val REFUSED: Set<String> = setOf(
        "manage_item", "set_reminder", "complete_task", "tag_place", "rename_place", "forget_place",
        "set_odometer", "log_service", "log_past_service", "set_maintenance_interval", "register_vehicle",
        "manage_vehicle", "clear_codes", "activate_garage", "log_build_entry", "log_meal", "log_workout_set",
        "log_bodyweight", "log_sleep", "set_meal_target", "set_sleep_target", "undo_last_log",
        "log_pending_transaction", "clear_pending_transaction", "set_category", "categorize_transactions",
        "set_budget", "set_goal", "close_goal", "accept_proposal", "generate_goal_plan", "accept_goal_plan",
        "start_voice_note", "stop_voice_note", "answer_call", "decline_call", "place_call", "email",
        "control_music", "control_volume", "play_music", "navigate", "change_trip", "end_trip",
        "show_app", "show_agenda_modal", "show_saved_places", "show_generated_view", "show_vault_item",
        "import_receipt", "look", "browser", "create_record", "update_record", "delete_record",
        "aspect_clerk", "create_aspect", "update_aspect", "ask_engine",
        // Its "check" can arm a watch; every other action sends or changes something.
        "claude_session",
        "set_companion_name", "set_personality", "set_driver", "finish_intro",
        // An action tool resolves to the tool its action names; one with no known action stays
        // itself and is refused (dispatch would refuse it too).
        "ask", "body_log", "car", "day", "goal", "look_up", "money", "phone", "place", "records", "trip",
        "voice_note",
    )

    const val MESSAGE = "Test mode: not done. LEGION is being tested from a computer, and this would change " +
        "something or act outside the app. Say plainly that it was not done because of test mode."

    /** Reads unless called with `stop` (which turns a spoken line off for good). */
    const val WHY = "why_did_you_say_that"

    /**
     * True when [name] (already resolved) with [args] only reads. A [READS] name must also not be
     * a write by [LiveToolbox.isMutatingCall] (a router called with intent=record is one).
     */
    fun isRead(name: String, args: JSONObject): Boolean = when {
        name == WHY -> !args.optBoolean("stop", false)
        name in READS -> !LiveToolbox.isMutatingCall(name, args)
        else -> READ_ACTIONS[name]?.contains(args.optString("action")) == true
    }

    /**
     * The refusal for a wire call ([wireName] as the model sent it), or null when it may run: a
     * read, or any tool when [allowWrites]. Never throws.
     */
    fun refusal(wireName: String, wireArgs: JSONObject, allowWrites: Boolean): JSONObject? {
        val (name, args) = LiveSurface.resolve(wireName, wireArgs)
        return if (allowWrites || isRead(name, args)) {
            null
        } else {
            JSONObject().put("success", false).put("test_mode", true).put("tool", name).put("message", MESSAGE)
        }
    }
}
