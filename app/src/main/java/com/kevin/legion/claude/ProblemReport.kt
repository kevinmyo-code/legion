package com.kevin.legion.claude

import java.security.MessageDigest
import java.time.Instant

/**
 * The words a problem report sends the Claude worker (alfred-builds ticket 01). Both doors (the
 * `claude_session` tool's `report` action and the chat panel's long-press Report) build the task
 * through here, so a report reads the same wherever it came from (ADR 0035).
 *
 * **Kevin's own words are the only free text, and a reply is named, never quoted.** A reply can
 * quote mail or a web page (CLAUDE.md section 7, third-party content is read-through only) and a
 * report is a stored task on the engine, so the reply travels as a [ReplyRef]: when it was added
 * and a short hash of its text. The hash cannot be read back into the text.
 */
object ProblemReport {
    const val SOURCE_VOICE = "voice"
    const val SOURCE_BUTTON = "button"

    const val EMPTY_NOTE = "Say what was wrong, even in a few words. Nothing was reported."
    const val NO_FIX_MACHINE =
        "No machine that can fix code is set up, so nothing was reported. The work laptop only summarises."

    /** The button's note is optional; this stands in so the worker still has words to read. */
    const val BUTTON_NO_NOTE = "No note. Kevin marked that reply as wrong."

    /** A reply on the panel: [atMillis] when the line was added, [hash] of its text (never the text). */
    data class ReplyRef(val atMillis: Long, val hash: String)

    /** First 8 hex digits of the text's SHA-256: enough to tell replies apart, useless to read back. */
    fun hashOf(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }

    /**
     * The task text. The first line is a free-text header the worker reads (the engine task kind is
     * still `ask`); the anchor line says what to match. No anchor (voice) says so.
     */
    fun request(note: String, source: String, reply: ReplyRef?, at: Instant): String = buildString {
        append("Problem report (a fix request, free text; the engine kind is ask). Reported by $source at $at.\n")
        if (reply != null) {
            append("Reply anchor: added ${Instant.ofEpochMilli(reply.atMillis)}, text hash ${reply.hash}. ")
            append("Match both exactly against the conversation records. If no record has that reply, ")
            append("say so in the result and do not pick the newest record.\n")
        } else {
            append("No reply anchor (spoken report). Use the conversation just before the report time ")
            append("only if it is unambiguous; otherwise say so in the result.\n")
        }
        append("Find the cause, fix it with a test, then ask before shipping.\nKevin's words: ")
        append(note.trim())
    }

    /** Said only when the engine took the task: filed, never fixed. */
    fun filed(taskSentence: String): String = "Report filed. $taskSentence The problem is not fixed yet."
}
