package com.kevin.legion.calendar

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.notes.formatDateOnly
import com.kevin.legion.ui.notes.formatDateTime
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.documentDate
import com.kevin.legion.util.shortDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads both places a due date lives and hands them to [DueList] (2026-11-15). The ONE reader
 * `read_calendar`'s `due` block and the Typeset home "due today" figure share, so the screen and
 * Alfred always count the same things the same way.
 */
object DueReader {
    /** Everything due from [from] to [to] (inclusive, local days), with what is overdue. */
    suspend fun read(
        context: Context,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis(),
    ): DueSummary {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val unreadable = mutableListOf<String>()
        val earliest = minOf(from, today.minusDays(DueList.TASK_OVERDUE_LOOKBACK_DAYS))
        val tasks = readOrNull("calendar tasks", unreadable) {
            CarDatabase.getDatabase(context).eventDao().activeByKindInLocalWindow(
                EventKind.TASK,
                earliest.atStartOfDay(zone).toInstant().toEpochMilli(),
                to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
                zone,
            )
        }.orEmpty()
        val todos = readOrNull("to-dos", unreadable) { NotesController.allItems(context) }.orEmpty()
        return summarize(tasks, todos, from, to, today, nowMs, zone, unreadable)
    }

    /** [read] over rows already in hand (`read_calendar` read the window's tasks itself). */
    @Suppress("LongParameterList") // the rows, the window, the clock and what could not be read
    fun summarize(
        tasks: List<Event>,
        todos: List<ListItem>,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
        nowMs: Long,
        zone: ZoneId,
        unreadable: List<String> = emptyList(),
    ): DueSummary {
        val inputs = tasks.mapNotNull { taskInput(it, zone, nowMs) } + todos.mapNotNull { todoInput(it, zone) }
        return DueList.summarize(inputs, from, to, today, nowMs, unreadable)
    }

    /**
     * A calendar task as [DueList] sees it: done when ticked, or when Canvas says submitted or excused
     * ([handedIn]; a discussion's own row takes Canvas's word only once its due time, [nowMs], has passed).
     */
    fun taskInput(e: Event, zone: ZoneId, nowMs: Long = System.currentTimeMillis()): DueInput? {
        val at = e.startsAt
        if (e.kind != EventKind.TASK || e.deleted || at == null) return null
        // A calendar row's all-day value is UTC midnight of its date (`activeByKindInLocalWindow`).
        val date = if (e.allDay) {
            Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate()
        } else {
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        }
        val label = if (e.allDay) documentDate(at) else "${shortDate(at)} ${clockTime(at)}"
        val atMs = if (e.allDay) date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 else at
        return DueInput(
            title = e.title,
            atMs = atMs,
            allDay = e.allDay,
            date = date,
            done = e.done || handedIn(e.structuredMeta, pastDue = nowMs >= atMs),
            source = DueSource.CALENDAR,
            whenLabel = label,
        )
    }

    /** A dated to-do (a reminder with a date) as [DueList] sees it; an undated one is never due. */
    fun todoInput(item: ListItem, zone: ZoneId): DueInput? {
        val at = item.startsAt
        if (at == null || item.deleted) return null
        // A reminder's all-day value is LOCAL midnight (unlike a calendar row).
        val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return DueInput(
            title = item.text,
            atMs = if (item.allDay) date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 else at,
            allDay = item.allDay,
            date = date,
            done = item.done,
            source = DueSource.TODO,
            recurring = item.repeatKind != null,
            whenLabel = if (item.allDay) formatDateOnly(at) else formatDateTime(at),
        )
    }

    /**
     * Canvas's own word that the work is in (`server/ingest/canvas.py` `is_submitted`, written as
     * `structured_meta.canvas_submitted`), a `submitted_at` on the row's evidence, or excused. Canvas
     * never ticks a row (the tick is his); this only keeps handed-in work out of "due".
     *
     * **A discussion's own row takes Canvas's word only once it is [pastDue].**
     * Canvas marks a discussion submitted at the FIRST post, but the row's due date is the last
     * obligation, the replies (`server/ingest/canvas.py` `initial_post_deadline`). Read as handed in,
     * a first-posted discussion dropped out of "due" while its replies were still owed; until its due
     * time it stays due unless he ticks it. Past it, the replies can no longer be
     * done, so Canvas's word holds again (no overdue nag for posted work). Its `first_post` sub-row is
     * the first post, and Canvas's word always holds there.
     */
    fun handedIn(meta: String?, pastDue: Boolean = false): Boolean {
        val o = meta?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return false
        if (o.optBoolean("excused", false)) return true
        if (!pastDue && isDiscussionReplies(o)) return false
        val submittedAt = o.opt("submitted_at")
        return o.optBoolean("canvas_submitted", false) || (submittedAt is String && submittedAt.isNotBlank())
    }

    /** A Canvas discussion's own row (its due date is the replies'), not its `first_post` sub-row. */
    private fun isDiscussionReplies(o: JSONObject): Boolean =
        !o.has("sub_deadline") && !o.has("parent_canvas_assignment_id") &&
            o.optString("submission_types").split(',').any { it.trim() == DISCUSSION_TYPE }

    private const val DISCUSSION_TYPE = "discussion_topic"

    /** The `due` block `read_calendar` returns ahead of its rows. */
    fun toJson(s: DueSummary): JSONObject {
        val items = JSONArray()
        for (e in s.open) {
            val o = JSONObject()
                .put("title", e.item.title)
                .put("due", DueList.dueWhen(e.item, s.today))
                .put("source", e.item.source.token)
                // Always said, true or false (2026-11-15): a missing key read as "maybe".
                .put("overdue", e.overdue)
            items.put(o)
        }
        // Counts first (2026-11-15): the audit line keeps only a result's first 400 characters, and
        // the open count is what a reader of that line (the voice eval's scorer) must always see.
        return JSONObject()
            .put("open", s.open.size)
            .put("overdue", s.overdueCount)
            .put("done", s.doneCount)
            // The local day the window was read against (never UTC; 2026-11-15).
            .also { o -> s.today?.let { o.put("today", dayAndDate(it)) } }
            .put("says", DueList.says(s))
            .put("open_items", items)
            .also { o -> if (s.unreadable.isNotEmpty()) o.put("unreadable", JSONArray(s.unreadable)) }
    }

    /** "Sunday 2026-11-15". */
    private fun dayAndDate(d: LocalDate): String = "${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} $d"

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed read is named, never an empty list
    private suspend fun <T> readOrNull(what: String, unreadable: MutableList<String>, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unreadable += what
            null
        }
}
