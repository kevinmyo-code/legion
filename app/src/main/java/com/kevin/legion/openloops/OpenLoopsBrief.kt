package com.kevin.legion.openloops

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one brief line about proposed to-dos (ADR 0085). **The rule decides, the model only words
 * it** (proactive-mode decisions 8 and 11): the rule is "pending proposals exist", and the facts
 * are the engine's stored rows, task and date only. No time field exists on the wire, so nothing
 * here can say how long ago he said it (compulsion test clause c, by construction); the
 * instruction forbids it in words as well, and forbids saying it is not done.
 *
 * Three states, never collapsed (proactive-mode decision 20): proposals (the line), none or
 * switched off (no line at all, so the subject is never invited), and unreadable (a line that
 * forbids the subject).
 *
 * The line also tells the model how his answer reaches a tool, so no new voice tool is declared
 * (the setup payload is at its ceiling): yes is `manage_item` add with the task's words, no is
 * `manage_item` remove, "stop proposing tasks" is `why_did_you_say_that`'s stop.
 */
object OpenLoopsBrief {

    /** Compulsion test (a): the fact he could check himself. */
    const val ANCHOR = "a task in your own words to Alfred, stored on the engine as a proposal you can list"

    /** Compulsion test (d): the one instruction that silences it, extraction included. */
    const val SILENCE_INSTRUCTION = "stop proposing tasks"

    /** What a spoken stop may call it (matched after "stop" and "the"/"my" are dropped). */
    val NAMES = listOf(
        "proposing tasks", "proposing to dos", "proposing todos", "task proposals", "proposed tasks",
        "proposed to dos", "to do proposals", "open loops", "proposals",
    )

    const val TITLE = "PROPOSED TO-DOS"

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

    fun dayWords(day: LocalDate): String = day.format(DAY)

    private fun named(p: Proposal): String =
        "\"${p.title}\"" + (p.dueDate?.let { " (date he named: ${dayWords(it)})" } ?: "")

    /** The spoken brief's section, or null when there is nothing to ask about. */
    fun forVoice(proposals: List<Proposal>): String? {
        if (proposals.isEmpty()) return null
        return "$TITLE, from his own words, not tracked unless he says yes: " +
            proposals.joinToString("; ") { named(it) } + ".\n" +
            "Ask once, as one short question, whether he wants each tracked; name the task, and its " +
            "date only if one is given. Never say when he said it, that it is still undone, or " +
            "anything about him; it is a question about tracking, nothing more. " +
            "Yes to one: call manage_item with action add, its words as item, and its date if it has " +
            "one. No: manage_item with action remove and its words; it is never proposed again. " +
            "\"$SILENCE_INSTRUCTION\": why_did_you_say_that with stop \"task proposals\"."
    }

    /** Unreadable is not empty: the model is told to leave the subject alone. */
    const val UNREADABLE_FOR_VOICE =
        "$TITLE could not be read just now: say nothing about proposed to-dos or tracking."

    /** The Brief modal's section (on screen; no instructions). */
    fun forScreen(proposals: List<Proposal>): String? {
        if (proposals.isEmpty()) return null
        return proposals.joinToString("; ") { p ->
            p.title + (p.dueDate?.let { " (${dayWords(it)})" } ?: "")
        } + ". Track or dismiss each in Settings, Proactive speech."
    }

    const val SCREEN_TITLE = "Proposed to-dos"

    const val UNREADABLE_FOR_SCREEN = "Proposed to-dos could not be read just now."

    /** True when [spoken] (a `why_did_you_say_that` stop argument) names these proposals. */
    fun namesThis(spoken: String): Boolean {
        val norm = spoken.lowercase().replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\b(the|my|your|stop|please|any|more)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (norm.isEmpty()) return false
        return NAMES.any { norm == it || norm.contains(it) }
    }
}
