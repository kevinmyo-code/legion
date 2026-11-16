package com.kevin.legion.calendar

import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Everything DUE, from both places a due date lives (2026-11-15). Kevin: *"what do i have due should
 * cover both calendar and todolists too no? like everything due"*, and *"why do i have 44 due today
 * on the home screen"*.
 *
 * A due date lives on a calendar TASK (`events.kind = task`: Canvas coursework, WebAssign, a task he
 * added) or on a dated to-do (a reminder [com.kevin.legion.data.local.ListItem] with `startsAt`).
 * Checklist lines carry no date at all, so nothing on a checklist is ever "due". Answering "what's
 * due" from one of the two places is how the 11-15 audit got "quiet on the calendar" while two
 * assignments were open: the model read reminders, then misread a task list. So both are merged HERE,
 * once, and `read_calendar` (voice) and the Typeset home figure both count from this.
 *
 * Pure (no Android, no JSON) so the rules are tested on the plain JVM; [DueReader] reads the rows.
 */
enum class DueSource(val token: String) {
    /** A calendar task (coursework, an assignment): `read_calendar`'s `kind = task`. */
    CALENDAR("calendar"),

    /** A dated to-do (a reminder): `read_list`'s rows. */
    TODO("todo"),
}

/**
 * One dated thing as [DueList] sees it. The caller resolves [date] (a calendar row's all-day value
 * is UTC midnight, a reminder's is local midnight: the two conventions stay in [DueReader]).
 */
data class DueInput(
    val title: String,
    val atMs: Long,
    val allDay: Boolean,
    val date: LocalDate,
    /** Ticked, or for a calendar task: Canvas says submitted or excused. */
    val done: Boolean,
    val source: DueSource,
    /** A repeating to-do: never overdue (its next occurrence is what is on file). */
    val recurring: Boolean = false,
    /** The due time in words, for the model and the screen ("Nov 15, 2026 23:59", "Nov 10"). */
    val whenLabel: String,
)

data class DueEntry(val item: DueInput, val overdue: Boolean)

/**
 * What is due from [from] to [to], plus anything still open from before it (overdue).
 * [open] is overdue first (oldest first), then by due time. Done things are only counted.
 * [unreadable] names a source that could not be read: its absence is never a clear day.
 */
data class DueSummary(
    val from: LocalDate,
    val to: LocalDate,
    val open: List<DueEntry>,
    val doneCount: Int,
    val unreadable: List<String> = emptyList(),
    /** The local day this was read on: what "today", "tomorrow" and "overdue" are relative to. */
    val today: LocalDate? = null,
) {
    val overdueCount: Int get() = open.count { it.overdue }

    /** Open and due inside the window itself (overdue ones from before it are not counted here). */
    val dueInWindowCount: Int get() = open.count { !it.overdue || it.item.date >= from }
}

object DueList {
    /**
     * How far back an open calendar task still counts as overdue. Canvas never closes a seeded row it
     * cannot match, so an unbounded look back would read out every stale assignment of the term. The
     * same 30 days HOME's to-do sheet already looks back over (`ContextHomeCalendarSource`). A
     * to-do has no such bound: he added it, and `read_list` has always read every one.
     */
    const val TASK_OVERDUE_LOOKBACK_DAYS = 30L

    /**
     * [inputs] are every candidate row (the window and the look back); this keeps what is due in
     * the window, adds what is overdue, drops one of two rows that are the same thing in both places,
     * and sorts. Overdue rows before [from] are added only when the window reaches [today] or later:
     * "what was due last week" is a question about last week.
     */
    @Suppress("LongParameterList") // the window, the clock and what could not be read
    fun summarize(
        inputs: List<DueInput>,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
        nowMs: Long,
        unreadable: List<String> = emptyList(),
    ): DueSummary {
        val taskFloor = today.minusDays(TASK_OVERDUE_LOOKBACK_DAYS)
        val kept = dedupe(inputs).mapNotNull { item ->
            val overdue = isOverdue(item, today, nowMs)
            val inWindow = item.date in from..to
            val before = item.date < from && overdue && !to.isBefore(today) &&
                (item.source == DueSource.TODO || !item.date.isBefore(taskFloor))
            if (inWindow || before) DueEntry(item, overdue) else null
        }
        val open = kept.filter { !it.item.done }
            .sortedWith(compareBy<DueEntry> { !it.overdue }.thenBy { it.item.atMs })
        val done = kept.count { it.item.done && it.item.date in from..to }
        return DueSummary(from, to, open, done, unreadable, today)
    }

    fun isOverdue(item: DueInput, today: LocalDate, nowMs: Long): Boolean = when {
        item.done || item.recurring -> false
        item.allDay -> item.date.isBefore(today)
        else -> item.atMs < nowMs
    }

    /**
     * One thing in both places (a coursework to-do he typed beside the Canvas row) is listed once:
     * same title (case, spacing and punctuation aside) on the same date. The calendar row is kept,
     * and it is done if either copy is.
     */
    fun dedupe(inputs: List<DueInput>): List<DueInput> {
        val out = LinkedHashMap<Pair<String, LocalDate>, DueInput>()
        for (item in inputs) {
            val key = normalise(item.title) to item.date
            val seen = out[key]
            out[key] = when {
                seen == null -> item
                seen.source == DueSource.CALENDAR || item.source != DueSource.CALENDAR ->
                    seen.copy(done = seen.done || item.done)
                else -> item.copy(done = item.done || seen.done)
            }
        }
        return out.values.toList()
    }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    fun normalise(title: String): String = title.lowercase().replace(NON_WORD, " ").trim()

    /**
     * The summary in a few sentences, for the model: an open item is due and not done, said so in
     * words so a list of them is never read as a quiet day. Overdue and not-yet-overdue are separate
     * sentences (2026-11-15 voice eval: asked "is anything overdue?" at night, flash-lite called
     * tonight's 23:59 work overdue and dropped yesterday's), each due time carries its weekday and,
     * near today, the word for it ("today", "tomorrow"), so nothing is left to date arithmetic.
     */
    fun says(s: DueSummary): String {
        val parts = mutableListOf<String>()
        val overdue = s.open.filter { it.overdue }
        val notYet = s.open.filter { !it.overdue }
        if (overdue.isNotEmpty()) {
            parts += "${overdue.size} OVERDUE (past due, not done): " +
                overdue.joinToString("; ") { line(it, s.today) } + "."
        }
        parts += when {
            notYet.isNotEmpty() ->
                "${notYet.size} open and due, not done, not overdue yet: " +
                    notYet.joinToString("; ") { line(it, s.today) } + "."
            overdue.isEmpty() -> "Nothing open is due in this window."
            else -> "Nothing else open is due in this window."
        }
        // Overdue work from before the window is looked for whenever the window reaches today.
        val lookedForOverdue = s.today?.let { !s.to.isBefore(it) } == true && s.unreadable.isEmpty()
        if (overdue.isEmpty() && lookedForOverdue) {
            parts += "Nothing is overdue."
        }
        if (s.doneCount > 0) parts += "${s.doneCount} already done."
        if (s.unreadable.isNotEmpty()) {
            parts += "Could not read ${s.unreadable.joinToString(" or ")}, so this may be missing items: say so."
        }
        return parts.joinToString(" ")
    }

    private fun line(e: DueEntry, today: LocalDate?): String =
        "${e.item.title} (due ${dueWhen(e.item, today)}, ${e.item.source.token})"

    /** "today, Sun Nov 15, 2026 23:59": the weekday always, the word for the day when it has one. */
    fun dueWhen(item: DueInput, today: LocalDate?): String {
        val day = item.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
        val word = when (today?.let { ChronoUnit.DAYS.between(it, item.date) }) {
            0L -> "today, "
            1L -> "tomorrow, "
            -1L -> "yesterday, "
            else -> ""
        }
        return "$word$day ${item.whenLabel}"
    }
}
