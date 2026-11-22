package com.kevin.legion.service.tools

import com.kevin.legion.service.LiveToolbox
import org.json.JSONObject

/**
 * Which resolved tool calls only READ: the one deny-by-default read list (ADR 0090 on the phone,
 * 2026-11-17). Moved here unchanged from `TestTurnPolicy` so the write guard
 * ([com.kevin.legion.service.ReadThroughWriteGuard]) and test turns share one list; a name that is
 * not listed here is a write, a call, a control or a screen.
 *
 * `TestTurnPolicyTest` fails on any reachable tool name that is in neither [READS] nor
 * `TestTurnPolicy.REFUSED`, so a new tool forces a decision instead of slipping through.
 */
object ToolReads {
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
        // The factory board's "where are we" (backlog intake 1122); `idea` writes.
        "claude_session" to setOf("where"),
    )

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
}
