package com.kevin.legion.service.testturn

import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.service.tools.ToolReads
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
/**
 * What a test turn may change (2026-11-22): nothing ([NONE]), anything ([ALL], the `writes` switch),
 * or only the tools named in [only] (`allow_tools`, resolved names). A named list is the safe one: a
 * scripted check that adds a list item can then never send mail or touch money, whatever the model
 * calls, because the refusal comes before the tool runs.
 */
data class TestWrites(val all: Boolean = false, val only: Set<String> = emptySet()) {
    /** [name] is a resolved tool name ([LiveSurface.resolve]). */
    fun allows(name: String): Boolean = all || name in only

    /** For the log: `none`, `all`, or the named tools. */
    override fun toString(): String = when {
        all -> "all"
        only.isEmpty() -> "none"
        else -> only.sorted().joinToString(",")
    }

    companion object {
        val NONE = TestWrites()
        val ALL = TestWrites(all = true)

        /** The door's extras: a non-blank `allow_tools` wins over `writes` and narrows it to those tools. */
        fun from(writes: Boolean, allowTools: String?): TestWrites {
            val named = allowTools.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            return when {
                named.isNotEmpty() -> TestWrites(only = named)
                writes -> ALL
                else -> NONE
            }
        }
    }
}

object TestTurnPolicy {
    /** Read-only whatever the arguments: [ToolReads.READS], the one read list (moved 2026-11-17). */
    val READS: Set<String> = ToolReads.READS

    /** [ToolReads.READ_ACTIONS]. */
    val READ_ACTIONS: Map<String, Set<String>> = ToolReads.READ_ACTIONS

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

    /** [ToolReads.WHY]. */
    const val WHY = ToolReads.WHY

    /** [ToolReads.isRead]: the same read list the ADR 0090 write guard uses. */
    fun isRead(name: String, args: JSONObject): Boolean = ToolReads.isRead(name, args)

    /**
     * The refusal for a wire call ([wireName] as the model sent it), or null when it may run: a
     * read, or a tool [writes] allows. Never throws.
     */
    fun refusal(wireName: String, wireArgs: JSONObject, writes: TestWrites): JSONObject? {
        val (name, args) = LiveSurface.resolve(wireName, wireArgs)
        return if (writes.allows(name) || isRead(name, args)) {
            null
        } else {
            JSONObject().put("success", false).put("test_mode", true).put("tool", name).put("message", MESSAGE)
        }
    }
}
