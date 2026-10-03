package com.kevin.legion.calendar

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Event

/**
 * The pure half of the `complete_task` voice tool: which calendar TASK rows a spoken phrase means,
 * and what the model is told about them. No Context, no Room, no clock - the toolbox reads the rows
 * and writes through [com.kevin.legion.backend.EventsAppointmentWriter.setDone] (the same call the
 * calendar UI's tick makes, ADR 0035), and everything that can be decided without either lives here
 * so it can be tested with plain [Event] values.
 *
 * **Matching is every-word, on whole tokens.** "math 2413" matches "MATH 2413 WebAssign 4.3" and
 * not "MATH 3391 ..." (2413 is not in it). A one-word query is deliberately loose ("math" matches
 * every math task) - that is what [Resolution.Ambiguous] is for: several matches change nothing
 * unless the caller said all_matches. A trailing "s" is dropped from both sides so Kevin's "maths
 * assignments" (2026-10-03) finds "MATH ... WebAssign": "assignment" is a [FILLER] word, since the
 * word is what he calls the whole kind of row and appears in almost no title.
 */
object TaskDoneMatcher {

    /** Words that describe the kind of row, not the one wanted. Dropped from the query only. */
    private val FILLER = setOf(
        "the", "my", "all", "a", "an", "of", "for", "to", "as", "task", "tasks",
        "assignment", "assignments", "done", "complete", "completed", "finished",
    )

    sealed interface Resolution {
        /** Query had no usable words after filler was dropped. Nothing is safe to match. */
        data object EmptyQuery : Resolution

        /** [toChange] is what to write; [alreadyThere] matched but were already in the target state. */
        data class Apply(val toChange: List<Event>, val alreadyThere: List<Event>) : Resolution

        /** Several matches and all_matches false. Nothing changes; the model asks which. */
        data class Ambiguous(val candidates: List<Event>) : Resolution

        /** Every match was already in the target state. Nothing changes. */
        data class AlreadyThere(val matches: List<Event>) : Resolution

        /** No task matched. [nearest] are the closest titles by shared words, possibly empty. */
        data class NoMatch(val nearest: List<Event>) : Resolution
    }

    /** A decimal such as "4.3" stays one token, or "4.4" would match every section 4. */
    private val TOKEN = Regex("[a-z0-9]+(?:\\.[0-9]+)*")

    /** Lowercased alphanumeric tokens, a plural "s" folded off words longer than three letters. */
    internal fun tokens(text: String): List<String> =
        TOKEN.findAll(text.lowercase()).map { stem(it.value) }.toList()

    private fun stem(t: String): String = if (t.length > MIN_STEM_LENGTH && t.endsWith("s")) t.dropLast(1) else t

    internal fun queryTokens(query: String): List<String> =
        TOKEN.findAll(query.lowercase()).map { it.value }.filter { it !in FILLER }.map { stem(it) }.toList()

    /**
     * [scoped] is the rows inside the caller's window; [all] is every task, used only to name the
     * nearest titles when the window held no match. Non-TASK and deleted rows are ignored here as
     * well as by the caller - an EVENT "just passes" and must never be completable.
     */
    fun resolve(
        scoped: List<Event>,
        all: List<Event>,
        query: String,
        target: Boolean,
        allMatches: Boolean,
    ): Resolution {
        val q = queryTokens(query)
        if (q.isEmpty()) return Resolution.EmptyQuery

        fun usable(list: List<Event>) = list.filter { it.kind == EventKind.TASK && !it.deleted }
        val matches = usable(scoped).filter { e -> tokens(e.title).toSet().containsAll(q) }
        if (matches.isEmpty()) {
            val nearest = usable(all)
                .map { it to tokens(it.title).toSet().count { t -> t in q } }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<Event, Int>> { it.second }
                        .thenBy { it.first.startsAt ?: Long.MAX_VALUE },
                )
                .take(NEAREST_LIMIT)
                .map { it.first }
            return Resolution.NoMatch(nearest)
        }

        val changeable = matches.filter { it.done != target }
        val already = matches.filter { it.done == target }
        if (changeable.isEmpty()) return Resolution.AlreadyThere(matches)
        if (changeable.size > 1 && !allMatches) return Resolution.Ambiguous(changeable)
        return Resolution.Apply(changeable, already)
    }

    const val NEAREST_LIMIT = 3
    private const val MIN_STEM_LENGTH = 3

    /** "Title (due Oct 5, 2026)" - [dateOf] formats an instant, so the caller picks UTC for an
     * all-day row and the device zone otherwise. A dateless task reads "no due date". */
    fun label(e: Event, dateOf: (Event) -> String?): String =
        e.title + " (" + (dateOf(e)?.let { "due $it" } ?: "no due date") + ")"

    /**
     * The sentence the model speaks from. [committed] is the rows
     * [com.kevin.legion.backend.EventsAppointmentWriter.setDone]
     * actually returned from; [failed] threw. [queued] is the subset whose push did not go through and
     * is waiting in the outbox. Outcome verbs (CLAUDE.md sec 7) appear only for [committed].
     */
    fun resultText(
        target: Boolean,
        committed: List<Event>,
        failed: List<Event>,
        queued: Set<Long>,
        alreadyThere: List<Event>,
        dateOf: (Event) -> String?,
    ): String {
        val state = if (target) "done" else "not done"
        val parts = mutableListOf<String>()
        if (committed.isNotEmpty()) {
            parts += "Marked $state: " + committed.joinToString("; ") { label(it, dateOf) } + "."
            if (queued.isNotEmpty()) {
                val subject = if (queued.size == committed.size) "this is" else "some of this is"
                parts += "The server could not be reached, so $subject " +
                    "saved on the phone and will sync when it is back."
            }
            // No "Canvas may still show it open" line, deliberately: server ingest/canvas.py (migration
            // 0005) never writes `done`, so a poll cannot undo this tick. LEGION does not push the
            // tick back to Canvas either, but nobody asked it to, and saying so would be noise.
        }
        if (failed.isNotEmpty()) {
            parts += "NOT changed, the write failed: " + failed.joinToString("; ") { label(it, dateOf) } + "."
        }
        if (alreadyThere.isNotEmpty()) {
            parts += "Already $state, left alone: " + alreadyThere.joinToString("; ") { label(it, dateOf) } + "."
        }
        return parts.joinToString(" ")
    }

    fun ambiguousText(candidates: List<Event>, dateOf: (Event) -> String?): String =
        "Nothing was changed. ${candidates.size} tasks match: " +
            candidates.joinToString("; ") { label(it, dateOf) } +
            ". Ask the user which one, or call again with all_matches true if they meant all of them."

    fun noMatchText(nearest: List<Event>, dateOf: (Event) -> String?): String =
        "Nothing was changed: no task matched that." + if (nearest.isEmpty()) {
            " No task title looked close either."
        } else {
            " Closest titles: " + nearest.joinToString("; ") { label(it, dateOf) } + "."
        }

    fun alreadyText(target: Boolean, matches: List<Event>, dateOf: (Event) -> String?): String =
        "Nothing was changed: already ${if (target) "done" else "not done"}: " +
            matches.joinToString("; ") { label(it, dateOf) } + "."

    const val EMPTY_QUERY_TEXT =
        "Nothing was changed. Which task? I need a title word or a course code, such as \"math\" or \"MATH 2413\"."
}
