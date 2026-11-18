package com.kevin.legion.service.cascade

import org.json.JSONObject

/**
 * The holding line says what is really happening (Kevin, 2026-11-17: "can the fillers be more like,
 * telling the user what its actually doing? like ok im calling the specialist agent now"). Chosen from
 * the tool call that has STARTED (name, and its action or domain where that is what differs), never
 * guessed before the model picks a tool, and never a result: "Asking the car specialist.", not "The
 * car is fine." (CLAUDE.md sec 7, the honesty clause: no outcome verb).
 *
 * Two timings:
 * - [slow]: a tool with its own model call or a network hop (the specialists, mail, the brief, the
 *   engine, Claude). Said as the call starts.
 * - otherwise (local reads and every write): said only if the tool is still running after
 *   [FAST_DELAY_MS]. A write can be refused by the guard at once; a line at its start could describe
 *   something that never ran.
 *
 * Every line is a scripted clip ([ScriptedLines.KEEP], warmed and kept on disk like the fillers), so it
 * plays from memory or not at all. Pure: no clock, no Android.
 */
internal object ToolFillers {
    /** A local tool still running after this long gets its line. */
    const val FAST_DELAY_MS = 700L

    /** For a tool with no line of its own. */
    val GENERIC = listOf("Working on it.", "On it.")

    /**
     * Key (`tool` or `tool:action`) to its lines, two each so the same words are not heard on every
     * call ([pick] never says one twice running). Each is under about 1.2 s spoken.
     */
    val LINES: Map<String, List<String>> = mapOf(
        "ask:ask_fleet" to listOf("Asking the car specialist.", "Checking with the car specialist."),
        "ask:ask_body" to listOf("Asking the health specialist.", "Checking your health log."),
        "ask:ask_goals" to listOf("Checking your goals.", "Looking at your goals."),
        "ask:ask_pantry" to listOf("Checking the pantry.", "Looking in the pantry."),
        "ask" to listOf("Asking the specialist.", "Checking with the specialist."),
        "look_up:ask_mail" to listOf("Checking your mail.", "Looking through your mail."),
        "look_up:ask_money" to listOf("Checking the bank feed.", "Looking at your accounts."),
        "look_up:ask_area" to listOf("Checking reports around you.", "Looking at local reports."),
        "look_up" to listOf("Looking that up.", "Checking on that."),
        "ask_mail" to listOf("Checking your mail.", "Looking through your mail."),
        "search_mail" to listOf("Checking your mail.", "Looking through your mail."),
        "day:read_calendar" to listOf("Checking your calendar.", "Looking at your calendar."),
        "day:get_sitrep" to listOf("Pulling your brief together.", "Getting your brief."),
        "day" to listOf("Checking your day.", "Looking at your day."),
        "ask_engine" to listOf("Checking with the engine.", "Asking the engine."),
        "claude_session" to listOf("Handing that to Claude.", "Passing that to Claude."),
        "browser" to listOf("Opening that page.", "Pulling up that page."),
        "car" to listOf("Talking to the car.", "Reading the car."),
        "records" to listOf("Checking your records.", "Looking at your records."),
        "manage_item" to listOf("Checking your reminders.", "Looking at your reminders."),
        "manage_checklist" to listOf("Checking your lists.", "Looking at your lists."),
    )

    /** Tools that never get a line: ending the conversation is itself the last thing said. */
    val SILENT: Set<String> = setOf("end_conversation")

    /**
     * Tools that use [GENERIC] on purpose (local and quick; a line only if one is still running after
     * [FAST_DELAY_MS]). Every tool the model can call is in [LINES], here, or in [SILENT]
     * (`ToolFillersTest`), so a new tool is a decision, not a silent default.
     */
    val GENERIC_TOOLS: Set<String> = setOf(
        "body_log", "clock", "control_music", "email", "goal", "memory", "money", "navigate", "phone", "place",
        "play_music", "trip", "voice_note",
    )

    /** Every line any tool may say, for warming and keeping ([ScriptedLines.KEEP]). */
    val ALL: List<String> = (LINES.values.flatten() + GENERIC).distinct()

    /** One started call as the filler needs it. */
    data class Call(val name: String, val args: JSONObject, val write: Boolean)

    /** What to say for a round's calls, and when; null: nothing. */
    data class Plan(val key: String, val lines: List<String>, val slow: Boolean)

    fun keyOf(name: String, args: JSONObject): String? {
        if (name in SILENT) return null
        val sub = args.optString("action").ifBlank { args.optString("domain") }
        val specific = "$name:$sub"
        return when {
            sub.isNotBlank() && specific in LINES -> specific
            name in LINES -> name
            else -> name
        }
    }

    /**
     * The line for this round: a slow read first (the one the wait is for), else the first other
     * call, said late. A write is never slow (the guard may refuse it at once).
     */
    fun plan(calls: List<Call>): Plan? {
        val keyed = calls.mapNotNull { c -> keyOf(c.name, c.args)?.let { it to c } }
        if (keyed.isEmpty()) return null
        val slow = keyed.firstOrNull { (_, c) -> !c.write && c.name in ScriptedLines.SLOW_TOOLS }
        val (key, _) = slow ?: keyed.first()
        return Plan(key, LINES[key] ?: GENERIC, slow = slow != null)
    }

    /**
     * What to say now and what to fetch for next time: the tool's own line when its audio is in
     * memory ([inHand]); else a [GENERIC] line that is, so a due filler is never silence (2026-11-18
     * review B7: after a voice change every tool's first use was silent), and the tool's own line is
     * fetched. Nothing to say only when no line at all is in memory.
     */
    fun choose(plan: Plan, last: String?, index: Int, inHand: (String) -> Boolean): Choice {
        val own = pick(plan.lines, last, index)
        if (inHand(own)) return Choice(say = own, fetch = null)
        val ready = GENERIC.filter(inHand)
        return Choice(say = if (ready.isEmpty()) null else pick(ready, last, index), fetch = own)
    }

    /** [say] now (null: nothing in memory), [fetch] for next time (null: nothing to fetch). */
    data class Choice(val say: String?, val fetch: String?)

    /** One of [lines], never [last] when there is another. */
    fun pick(lines: List<String>, last: String?, index: Int): String {
        val options = lines.filter { it != last }.ifEmpty { lines }
        return options[Math.floorMod(index, options.size)]
    }
}
