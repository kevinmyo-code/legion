package com.kevin.legion.service.tools

import com.kevin.legion.service.tools.fleet.Declarations as FleetDeclarations
import com.kevin.legion.service.tools.core.Declarations as CoreDeclarations
import com.kevin.legion.service.tools.music.Declarations as MusicDeclarations
import com.kevin.legion.service.tools.nav.Declarations as NavDeclarations
import com.kevin.legion.service.tools.places.Declarations as PlacesDeclarations
import com.kevin.legion.service.tools.voicenotes.Declarations as VoiceNotesDeclarations
import com.kevin.legion.service.tools.money.Declarations as MoneyDeclarations
import com.kevin.legion.service.tools.pantry.Declarations as PantryDeclarations
import com.kevin.legion.service.tools.body.Declarations as BodyDeclarations
import com.kevin.legion.service.tools.day.Declarations as DayDeclarations
import com.kevin.legion.service.tools.mail.Declarations as MailDeclarations
import com.kevin.legion.service.tools.calls.Declarations as CallsDeclarations
import com.kevin.legion.service.tools.goals.Declarations as GoalsDeclarations
import org.json.JSONObject

/**
 * Every LiveToolbox-owned tool declaration, by domain (architecture review 2026-11-10, item 8).
 * [DECLARATION_ORDER] is the order `LiveToolbox.allDeclarations()` emitted before the split,
 * kept so the Live setup message is byte-identical; [TOOL_DOMAIN] names each tool's file.
 */
internal val DECLARATION_ORDER: List<String> = listOf(
    "get_vehicle_data", "diagnose_codes", "get_codes", "get_code_history",
    "read_vehicle_sensor", "get_current_time", "triage_symptom", "get_health",
    "clear_codes", "get_trend", "get_mpg", "check_readiness",
    "check_cold_start", "get_next_service", "ask_maintenance", "control_music",
    "control_volume", "end_conversation", "navigate", "change_trip",
    "trip_status", "end_trip", "play_music", "browse_my_music",
    "get_music_queue", "show_app", "set_reminder", "tag_place",
    "rename_place", "forget_place", "show_saved_places", "start_voice_note",
    "stop_voice_note", "read_voice_note", "list_voice_notes", "get_balance",
    "list_recent_transactions", "categorize_transactions", "set_category", "log_pending_transaction",
    "list_pending_transactions", "clear_pending_transaction", "import_receipt", "list_recent_groceries",
    "get_grocery_spend", "log_workout_set", "log_bodyweight", "get_workout_gap",
    "list_recent_workouts", "log_meal", "set_meal_target", "set_budget",
    "list_budget_categories", "get_monthly_spend", "get_meal_gap", "list_recent_meals",
    "log_sleep", "set_sleep_target", "get_sleep_gap", "list_recent_sleep",
    "undo_last_log", "set_odometer", "log_service", "log_past_service",
    "set_maintenance_interval", "lookup_vin", "get_specs", "check_recalls",
    "register_vehicle", "manage_item", "manage_checklist", "get_last_ticked",
    "read_list", "show_agenda_modal", "show_generated_view", "log_build_entry",
    "list_build_history", "get_spend", "activate_garage", "get_current_location",
    "area_info", "get_reported_crime_history", "list_vehicles", "manage_vehicle",
    "search_mail", "read_mail", "track_package", "flight_status",
    "why_did_you_say_that", "get_sitrep", "answer_call", "decline_call",
    "place_call", "read_calendar", "complete_task", "set_goal",
    "list_goals", "close_goal", "generate_goal_plan", "accept_goal_plan",
    "ask_advisor", "accept_proposal",
)

/** Tool name to the domain directory its declaration lives in. */
internal val TOOL_DOMAIN: Map<String, String> = mapOf(
    "get_vehicle_data" to "fleet", "diagnose_codes" to "fleet", "get_codes" to "fleet",
    "get_code_history" to "fleet", "read_vehicle_sensor" to "fleet", "get_current_time" to "core",
    "triage_symptom" to "fleet", "get_health" to "fleet", "clear_codes" to "fleet",
    "get_trend" to "fleet", "get_mpg" to "fleet", "check_readiness" to "fleet",
    "check_cold_start" to "fleet", "get_next_service" to "fleet", "ask_maintenance" to "fleet",
    "control_music" to "music", "control_volume" to "music", "end_conversation" to "core",
    "navigate" to "nav", "change_trip" to "nav", "trip_status" to "nav",
    "end_trip" to "nav", "play_music" to "music", "browse_my_music" to "music",
    "get_music_queue" to "music", "show_app" to "core", "set_reminder" to "places",
    "tag_place" to "places", "rename_place" to "places", "forget_place" to "places",
    "show_saved_places" to "places", "start_voice_note" to "voicenotes", "stop_voice_note" to "voicenotes",
    "read_voice_note" to "voicenotes", "list_voice_notes" to "voicenotes", "get_balance" to "money",
    "list_recent_transactions" to "money", "categorize_transactions" to "money", "set_category" to "money",
    "log_pending_transaction" to "money",
    "list_pending_transactions" to "money",
    "clear_pending_transaction" to "money",
    "import_receipt" to "pantry", "list_recent_groceries" to "pantry", "get_grocery_spend" to "pantry",
    "log_workout_set" to "body", "log_bodyweight" to "body", "get_workout_gap" to "body",
    "list_recent_workouts" to "body", "log_meal" to "body", "set_meal_target" to "body",
    "set_budget" to "money", "list_budget_categories" to "money", "get_monthly_spend" to "money",
    "get_meal_gap" to "body", "list_recent_meals" to "body", "log_sleep" to "body",
    "set_sleep_target" to "body", "get_sleep_gap" to "body", "list_recent_sleep" to "body",
    "undo_last_log" to "body", "set_odometer" to "fleet", "log_service" to "fleet",
    "log_past_service" to "fleet", "set_maintenance_interval" to "fleet", "lookup_vin" to "fleet",
    "get_specs" to "fleet", "check_recalls" to "fleet", "register_vehicle" to "fleet",
    "manage_item" to "day", "manage_checklist" to "day", "get_last_ticked" to "day",
    "read_list" to "day", "show_agenda_modal" to "day", "show_generated_view" to "core",
    "log_build_entry" to "fleet", "list_build_history" to "fleet", "get_spend" to "fleet",
    "activate_garage" to "core", "get_current_location" to "core", "area_info" to "core",
    "get_reported_crime_history" to "core", "list_vehicles" to "fleet", "manage_vehicle" to "fleet",
    "search_mail" to "mail", "read_mail" to "mail", "track_package" to "mail",
    "flight_status" to "mail", "why_did_you_say_that" to "core", "get_sitrep" to "core",
    "answer_call" to "calls", "decline_call" to "calls", "place_call" to "calls",
    "read_calendar" to "day", "complete_task" to "day", "set_goal" to "goals",
    "list_goals" to "goals", "close_goal" to "goals", "generate_goal_plan" to "goals",
    "accept_goal_plan" to "goals", "ask_advisor" to "goals", "accept_proposal" to "goals",
)

/** Every domain file's declarations, keyed by tool name. */
internal fun declarationsByName(): Map<String, JSONObject> = listOf(
    FleetDeclarations.declarations(),
    CoreDeclarations.declarations(),
    MusicDeclarations.declarations(),
    NavDeclarations.declarations(),
    PlacesDeclarations.declarations(),
    VoiceNotesDeclarations.declarations(),
    MoneyDeclarations.declarations(),
    PantryDeclarations.declarations(),
    BodyDeclarations.declarations(),
    DayDeclarations.declarations(),
    MailDeclarations.declarations(),
    CallsDeclarations.declarations(),
    GoalsDeclarations.declarations(),
).flatten().associateBy { it.getString("name") }
