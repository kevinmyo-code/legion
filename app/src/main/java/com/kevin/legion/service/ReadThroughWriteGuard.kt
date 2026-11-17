package com.kevin.legion.service

import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.service.tools.ToolReads
import org.json.JSONObject

/**
 * ADR 0090 on the phone (2026-11-17): **no lasting or outbound change follows outside text in the
 * same turn.** One check, before every resolved tool call on every path (Live, the cascade on cloud
 * or laptop voice, typed chat), in `LiveSessionController.runTool`.
 *
 * Kevin's ruling, 2026-11-17, "Block lasting + outbound": after an outside read, refuse only what
 * lasts or leaves the phone; still allow navigation, timers, music, volume, screens and reading more
 * of a web page. So in a turn marked read-through (mail, a web page or search, the brief, a camera
 * look, a GitHub result through `ask_engine`, or the carry turn after one) a call runs when it is a
 * read ([ToolReads]) or listed in [ALLOWED_AFTER_READ] / [ALLOWED_AFTER_READ_ACTIONS]. Everything
 * else is refused in words and nothing runs: memories, watches, records, to-dos, logs, goals, mail,
 * calls, the garage, Claude work, the engine, app installs, settings, and anything new until it is
 * classified (deny by default). [REFUSED_AFTER_READ] names the refused tools so a test can tell a
 * decision from a tool nobody classified.
 *
 * The engine's `guards.py` is stricter for now (every write refused); a known difference.
 *
 * The per-tool checks inside `memory`, `watch`, `claude_session`, `manage_item` (a proposed to-do)
 * and `generate_goal_plan` stay as a second lock for callers that reach [LiveToolbox.dispatch]
 * directly; on the three voice and typed paths this guard answers first.
 */
object ReadThroughWriteGuard {

    /**
     * Allowed after an outside read, whatever the action: nothing here lasts past the moment or
     * sends anything off the phone beyond what the ruling accepts (a route request for navigation).
     */
    val ALLOWED_AFTER_READ: Set<String> = setOf(
        "navigate", "change_trip", "end_trip",
        "clock",
        "control_music", "play_music", "control_volume", "books",
        "show_app", "show_agenda_modal", "show_saved_places", "show_generated_view", "show_vault_item",
        "import_receipt", "look",
        // Answering or declining a call that is already ringing places nothing.
        "answer_call", "decline_call",
    )

    /**
     * Allowed after an outside read for these actions only. The browser may keep reading the page
     * it has; `open` is refused because an address can carry text out in its query string, and
     * `click` / `type` act on the page.
     */
    val ALLOWED_AFTER_READ_ACTIONS: Map<String, Set<String>> = mapOf(
        "browser" to setOf("read", "scroll", "back", "close"),
    )

    /** Refused after an outside read: each lasts or leaves the phone (for a mixed tool, its other actions). */
    val REFUSED_AFTER_READ: Set<String> = setOf(
        // To-dos, reminders, places.
        "manage_item", "manage_checklist", "set_reminder", "complete_task", "tag_place", "rename_place",
        "forget_place",
        // Fleet, body and money logs.
        "set_odometer", "log_service", "log_past_service", "set_maintenance_interval", "register_vehicle",
        "manage_vehicle", "clear_codes", "log_build_entry", "log_meal", "log_workout_set", "log_bodyweight",
        "log_sleep", "set_meal_target", "set_sleep_target", "undo_last_log", "log_pending_transaction",
        "clear_pending_transaction", "set_category", "categorize_transactions", "set_budget", "bought_log",
        // Goals and proposals.
        "set_goal", "close_goal", "accept_proposal", "generate_goal_plan", "accept_goal_plan",
        // Memory, watches, records, the engine, Claude.
        "memory", "watch", "create_record", "update_record", "delete_record", "aspect_clerk",
        "create_aspect", "update_aspect", "ask_engine", "claude_session",
        // Outbound or physical: mail, calls, the garage, an app install, a browser open/click/type.
        "email", "place_call", "activate_garage", "app_update", "browser",
        // Recordings and settings that last.
        "start_voice_note", "stop_voice_note", "set_companion_name", "set_personality", "set_driver",
        "finish_intro", ToolReads.WHY,
        // An action tool with no known action stays itself (dispatch refuses it too).
        "ask", "body_log", "car", "day", "goal", "look_up", "money", "phone", "place", "records", "trip",
        "voice_note",
    )

    /** True when [name] (resolved) with [args] may run in a turn that read outside text. */
    fun runsAfterRead(name: String, args: JSONObject): Boolean =
        ToolReads.isRead(name, args) ||
            name in ALLOWED_AFTER_READ ||
            ALLOWED_AFTER_READ_ACTIONS[name]?.contains(args.optString("action")) == true

    /** The engine's `read_through.WRITE_REFUSAL`, with the phone's own sources named. */
    const val WRITE_REFUSAL = "Nothing was done: %s did not run. This turn read outside text (mail, a web " +
        "page or search, the brief's news, a camera look or a GitHub result), which can carry instructions " +
        "that are not the person's, so anything that lasts or leaves the phone waits for their next " +
        "message. Say so; they can simply ask again."

    /**
     * The refusal for [wireName]/[wireArgs] (as the model sent it; resolved here) when [readThrough]
     * is set and the call may not run after an outside read ([runsAfterRead]), else null.
     * [readThrough] is the turn's mark as it stood before this call's own mark, so a read-through
     * tool that also acts does not refuse itself.
     */
    fun refusal(wireName: String, wireArgs: JSONObject, readThrough: Boolean): JSONObject? {
        val (name, args) = LiveSurface.resolve(wireName, wireArgs)
        if (!readThrough || runsAfterRead(name, args)) return null
        // A memory keeps the words it always had (the engine special-cases `remember` the same way).
        val words = if (name == MemoryToolbox.TOOL_NAME && args.optString("action") == "remember") {
            LiveToolbox.REMEMBER_MAIL_REFUSAL
        } else {
            WRITE_REFUSAL.format(name)
        }
        return JSONObject()
            .put("success", false)
            .put("read_through", true)
            .put("tool", name)
            .put("message", words)
    }

    /**
     * Whether a call, as the model SENT it, reads outside text: a folded action tool (`look_up`
     * with `domain: ask_mail`, `day` with `get_sitrep`, `track_package` or `flight_status`) is
     * judged on the tool it stands for ([LiveSurface.resolve]) against
     * [LiveToolbox.EPISODIC_EXCLUDED_TOOLS], the way Live and typed already judge it. The cascade's
     * taint check goes through this (2026-11-17, gap 1).
     */
    fun isReadThroughCall(name: String, args: JSONObject): Boolean =
        GeminiLiveSession.isEpisodicExcludedTool(LiveSurface.resolve(name, args).name)
}
