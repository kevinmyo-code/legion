package com.kevin.legion.service.tools.day

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.notes.ItemMatch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.bestTextMatches
import com.kevin.legion.service.tools.day.EventRemovalWords.isPlainNo
import com.kevin.legion.service.tools.day.EventRemovalWords.normalise
import com.kevin.legion.service.tools.day.EventRemovalWords.whenPhrase
import com.kevin.legion.vehicle.ClearCodesVoiceGate
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/**
 * `manage_item remove` for a calendar entry (2026-11-22: asked to remove an entry he had made by
 * voice, the assistant said it could not; remove only reached reminders and to-dos).
 *
 * **What may be removed is what the screens remove.** The Calendar screen's Schedule rows and the
 * Matrix calendar's editor delete `kind = event` rows only, class rows included, through
 * [NotesController.removeAppointment] (tombstone, push, outbox). Coursework (`kind = task`, Canvas)
 * has no delete on either screen, so it has none here: the result says so and offers done.
 *
 * **Asked first, removed on his yes in a later turn.** The first call matches and records which
 * row the question named; nothing is removed. A later `remove` call removes exactly that row, never
 * the model's new arguments, and only when: the question is younger than [TTL_MS], his words are
 * not the asking turn's own, they are a plain yes ([ClearCodesVoiceGate.isExplicitYes]) carried by
 * enough voice, and the call's item still names the entry asked about. Anything else is a fresh
 * request: it asks again, and never removes. The same shape as the module clear (`DiagVoice`), with
 * its own words. No `confirmed` parameter: the tool declaration has no room for one.
 *
 * **No series.** A calendar row is one occurrence; repeats are separate rows with the same title.
 * Only the asked-about row goes, and the question says how many others stay. A row that itself
 * carries a repeat goes whole, and the question says that too.
 */
internal object EventRemoval {
    /** How long a remove question stays answerable; the clear question's window. */
    const val TTL_MS = ClearCodesVoiceGate.TTL_MS

    /** With no day named, the entries looked through: today and this many days after. */
    const val LOOK_AHEAD_DAYS = 60L

    /** At most this many entries are named when asking which one. */
    const val MAX_NAMED = 3

    const val ASK_AND_WAIT =
        "Nothing is removed yet. Say this question and stop. Only if his next words are a plain yes, " +
            "call manage_item remove again with the same item: that removes exactly this entry. Never " +
            "say it is removed before that call succeeds."

    private data class Ask(val eventId: Long, val title: String, val atMs: Long, val askWords: String)

    @Volatile private var pending: Ask? = null

    /** Test seam: the clock the question's age is read on. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    fun reset() {
        pending = null
    }

    /**
     * `manage_item remove` for [phrase], before the reminder path. [day] is the day he named, if
     * any; [reminders] is what the same phrase matched among reminders. Null when no calendar entry
     * is involved and no question waits for this answer: the reminder and to-do path runs as before.
     */
    suspend fun remove(
        context: Context,
        phrase: String,
        day: LocalDate?,
        userText: String?,
        reminders: ItemMatch,
    ): JSONObject? {
        answer(context, phrase, userText)?.let { return it }
        val zone = RelativeDay.clock().zone
        val today = RelativeDay.today()
        val (from, to) = window(day, today)
        val dao = CarDatabase.getDatabase(context).eventDao()
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val inWindow = dao.activeByKindInLocalWindow(EventKind.EVENT, fromMs, toMs, zone)
        val events = bestTextMatches(phrase, inWindow) { it.title }
        val reminderNames = when (reminders) {
            is ItemMatch.Resolved -> listOf(reminders.item.text)
            is ItemMatch.Ambiguous -> reminders.candidates.map { it.text }
            ItemMatch.NoMatch -> emptyList()
        }
        return when {
            events.isEmpty() && reminderNames.isEmpty() -> taskOnly(dao, phrase, fromMs, toMs, zone)
            events.isEmpty() -> null
            reminderNames.isNotEmpty() -> whichOne(events, reminderNames, today, zone)
            events.size > 1 -> whichOne(events, emptyList(), today, zone)
            else -> ask(dao, events.single(), userText, today, zone)
        }
    }

    /** The line for a remove that matched nothing anywhere, naming where it looked. */
    fun nothingFound(phrase: String, day: LocalDate?): String {
        val (from, to) = window(day, RelativeDay.today())
        val where = if (from == to) {
            "on ${EventRemovalWords.dayWords(from, RelativeDay.today())}"
        } else {
            "from today through ${to.format(EventRemovalWords.SHORT_DAY)}"
        }
        return "Nothing was removed: no reminder, to-do or calendar entry $where matches \"$phrase\". " +
            "If it is on another day, ask with that day."
    }

    private fun window(day: LocalDate?, today: LocalDate): Pair<LocalDate, LocalDate> =
        if (day != null) day to day else today to today.plusDays(LOOK_AHEAD_DAYS)

    /** His answer to a waiting question, or null when this call is a fresh request. */
    @Suppress("ReturnCount") // each condition that makes it a fresh request returns on its own line
    private suspend fun answer(context: Context, phrase: String, userText: String?): JSONObject? {
        val ask = pending ?: return null
        val words = normalise(userText)
        // Too old, the asking turn calling again, or no words to judge (a tool call with no turn).
        if (clock() - ask.atMs > TTL_MS) pending = null
        if (pending == null || words.isEmpty() || words == ask.askWords) return null
        pending = null
        val sameEntry = bestTextMatches(phrase, listOf(ask.title)) { it }.isNotEmpty()
        if (sameEntry && isPlainNo(userText.orEmpty())) {
            return fail("Nothing was removed: his answer was no, so \"${ask.title}\" stays on the calendar.")
        }
        return if (sameEntry && plainYes(userText.orEmpty())) removeAsked(context, ask) else null
    }

    /** A plain yes, carried by enough voice when the turn measured it (the clear's rule). */
    private suspend fun plainYes(text: String): Boolean {
        val voiced = kotlin.coroutines.coroutineContext[ClearCodesVoiceGate.HeardVoice]?.voicedMs
        return (voiced == null || voiced >= ClearCodesVoiceGate.YES_MIN_VOICED_MS) &&
            ClearCodesVoiceGate.isExplicitYes(text)
    }

    /** Removes exactly the row the question named, through the Calendar screen's funnel. */
    private suspend fun removeAsked(context: Context, ask: Ask): JSONObject {
        val row = CarDatabase.getDatabase(context).eventDao().getById(ask.eventId)
            ?.takeIf { !it.deleted && it.kind == EventKind.EVENT }
            ?: return fail("Nothing was removed: \"${ask.title}\" is no longer on the calendar.")
        val whenWords = whenPhrase(row, RelativeDay.today(), RelativeDay.clock().zone)
        return if (NotesController.removeAppointment(context, row.id)) {
            JSONObject().put("success", true).put("title", row.title)
                .put("message", "Removed \"${row.title}\" ($whenWords) from the calendar.")
        } else {
            fail("Something went wrong: \"${row.title}\" is still on the calendar.")
        }
    }

    private suspend fun ask(
        dao: com.kevin.legion.data.local.EventDao,
        row: Event,
        userText: String?,
        today: LocalDate,
        zone: ZoneId,
    ): JSONObject {
        pending = Ask(row.id, row.title, clock(), normalise(userText))
        val others = dao.getActiveByKind(EventKind.EVENT)
            .count { it.id != row.id && it.title.trim().equals(row.title.trim(), ignoreCase = true) }
        val scope = when {
            row.repeatKind != null ->
                " It repeats, and the whole series goes with it: one occurrence cannot be removed on its own."
            others == 1 -> " Only this one goes; the other \"${row.title}\" entry stays."
            others > 1 -> " Only this one goes; the other $others \"${row.title}\" entries stay."
            else -> ""
        }
        return asking("Remove \"${row.title}\", ${whenPhrase(row, today, zone)}?$scope")
            .put("instruction", ASK_AND_WAIT)
    }

    private fun whichOne(events: List<Event>, reminderNames: List<String>, today: LocalDate, zone: ZoneId): JSONObject {
        pending = null
        val named = reminderNames.map { "the reminder \"$it\"" } +
            events.sortedBy { it.startsAt ?: 0L }.map { "\"${it.title}\", ${whenPhrase(it, today, zone)}" }
        val more = named.size - MAX_NAMED
        val tail = if (more > 0) "; and $more more: a day narrows it" else ""
        return asking("Nothing was removed. Which one? " + named.take(MAX_NAMED).joinToString("; ") + tail + ".")
    }

    /** Nothing matched among events or reminders: coursework by that name gets its own answer. */
    private suspend fun taskOnly(
        dao: com.kevin.legion.data.local.EventDao,
        phrase: String,
        fromMs: Long,
        toMs: Long,
        zone: ZoneId,
    ): JSONObject? {
        val inWindow = dao.activeByKindInLocalWindow(EventKind.TASK, fromMs, toMs, zone)
        val tasks = bestTextMatches(phrase, inWindow) { it.title }
        if (tasks.isEmpty()) return null
        return fail(
            "Nothing was removed: \"${tasks.first().title}\" is coursework, and coursework is not deleted, " +
                "by voice or on the Calendar screen. It can be marked done.",
        )
    }

    private fun asking(message: String): JSONObject =
        // `needs_answer`: his next one word may answer it (the cascade's LoneWordWriteGuard.asks).
        fail(message).put("needs_answer", true)

    private fun fail(message: String): JSONObject = JSONObject().put("success", false).put("message", message)
}
