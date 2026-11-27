package com.kevin.legion.claude

import java.time.Instant

/**
 * The words a problem report sends the Claude worker (alfred-builds ticket 01). Both doors (the
 * `claude_session` tool's `report` action and the chat panel's long-press Report) build the task
 * through here, so a report reads the same wherever it came from (ADR 0035).
 *
 * **Kevin's note is the only content.** Never the reply's text: a reply can quote mail or a web page
 * (CLAUDE.md section 7, third-party content is read-through only), and a report is a stored task on
 * the engine. The worker finds the turn from the time and the reply's position instead.
 */
object ProblemReport {
    const val SOURCE_VOICE = "voice"
    const val SOURCE_BUTTON = "button"

    const val EMPTY_NOTE = "Say what was wrong, even in a few words. Nothing was reported."
    const val NO_FIX_MACHINE =
        "No machine that can fix code is set up, so nothing was reported. The work laptop only summarises."

    /** The button's note is optional; this stands in so the worker still has words to read. */
    const val BUTTON_NO_NOTE = "No note. Kevin marked that reply as wrong."

    /** The task text: a header the worker's fix skill keys on, then his words verbatim. */
    fun request(note: String, source: String, replyIndex: Int?, at: Instant): String = buildString {
        append("Problem report (kind: fix). Reported by $source at $at")
        if (replyIndex != null) append(", about reply number ${replyIndex + 1} of that conversation")
        append(".\nFind the conversation turn from that time, find the cause, fix it with a test, ")
        append("then ask before shipping.\nKevin's words: ")
        append(note.trim())
    }

    /** Said only when the engine took the task: filed, never fixed. */
    fun filed(taskSentence: String): String = "Report filed. $taskSentence The problem is not fixed yet."
}
