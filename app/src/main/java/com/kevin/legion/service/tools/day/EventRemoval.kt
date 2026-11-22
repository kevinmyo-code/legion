package com.kevin.legion.service.tools.day

import android.content.Context
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.EventDao
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.TextMatches
import com.kevin.legion.notes.tieredTextMatches
import com.kevin.legion.service.tools.day.EventRemovalWords.fail
import com.kevin.legion.service.tools.day.EventRemovalWords.whenPhrase
import com.kevin.legion.vehicle.ClearCodesVoiceGate
import java.time.LocalDate
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
 * **Reminders keep their remove.** The phrase is matched against reminders and entries, each at a
 * tier (exact, part of the title, shared words); the stronger tier wins, so an exact reminder is
 * removed as before. Equal tiers ask which one, and his words settle it ("the reminder", "the
 * calendar one"), so the same call never asks the same question twice.
 *
 * **Asked first, removed on his yes in the next turn.** The first call records which row the question
 * named (its id, title and start) and the turn it was asked in; nothing is removed. A later `remove`
 * call removes exactly that row, never the model's new arguments, and only when all hold:
 *  - it is the very next turn he started ([turnStarted]) and the question is younger than [TTL_MS];
 *  - his whole answer is a plain yes and nothing more ([EventRemovalWords.isBareYes]): "yes, the
 *    Friday one" or "yeah, the other one" is a new request, asked again;
 *  - the clip carried enough voice (the clear's rule, [ClearCodesVoiceGate.YES_MIN_VOICED_MS]);
 *  - the call names the same row: its title (exactly or in part, never one shared word) and, when it
 *    gives a day, that row's day;
 *  - the row still has the title and start the question read back (else it asks again).
 * The question is spent by the first answer of any kind, by any other tool call (LiveToolbox), by a
 * second turn, and by the conversation's end. The module clear (`DiagVoice`) has the same shape; no
 * `confirmed` parameter, since the tool declaration has no room for one.
 *
 * **No series.** A calendar row is one occurrence; repeats are separate rows with the same title.
 * Only the asked-about row goes, and the question says how many others stay. A row that itself
 * carries a repeat goes whole, and the question says that too.
 */
internal object EventRemoval {
    /** How long a remove question stays answerable; the clear question's window. */
    const val TTL_MS = ClearCodesVoiceGate.TTL_MS

    /** At most this many entries are named when asking which one. */
    const val MAX_NAMED = 3

    const val ASK_AND_WAIT =
        "Nothing is removed yet. Say this question and stop. Only if his next words are a plain yes and " +
            "nothing more, call manage_item remove again with the same item: that removes exactly this " +
            "entry. Never say it is removed before that call succeeds."

    private data class Ask(val eventId: Long, val title: String, val startsAt: Long?, val turn: Long, val atMs: Long)

    @Volatile private var pending: Ask? = null

    /** His turns so far, counted by the voice session ([turnStarted]); a question is answerable in the next. */
    @Volatile private var turn = 0L

    /** The turn he is in (EventMove reads it for "the entry just discussed"). */
    val currentTurn: Long get() = turn

    /** Test seam: the clock the question's age is read on. */
    @Volatile internal var clock: () -> Long = System::currentTimeMillis

    /** No question waits any more: another tool ran, or the conversation ended. */
    fun reset() {
        pending = null
    }

    /** He started a turn (the cascade, for his words only). A question survives into one turn, no more. */
    fun turnStarted() {
        turn++
        val ask = pending
        if (ask != null && turn > ask.turn + 1) pending = null
    }

    /**
     * `manage_item remove` for [phrase], before the reminder path. [day] is the day he named, if any;
     * [reminders] are his open reminders. Null when a reminder is the better match, or nothing
     * involves the calendar: the reminder and to-do path runs as before.
     */
    suspend fun remove(
        context: Context,
        phrase: String,
        day: LocalDate?,
        userText: String?,
        reminders: List<ListItem>,
    ): JSONObject? {
        answer(context, phrase, day, userText)?.let { return it }
        val zone = RelativeDay.clock().zone
        val (from, to) = EventRemovalWords.window(day, RelativeDay.today())
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val dao = CarDatabase.getDatabase(context).eventDao()
        val events = tieredTextMatches(phrase, dao.activeByKindInLocalWindow(EventKind.EVENT, fromMs, toMs, zone)) {
            it.title
        }
        val dueThatDay = reminders.filter { day == null || EventRemovalWords.localDay(it.startsAt) == day }
        val matched = tieredTextMatches(phrase, dueThatDay) { it.text }
        return pick(dao, events, matched, userText) ?: if (events.items.isEmpty() && matched.items.isEmpty()) {
            taskOnly(dao, phrase, fromMs, toMs)
        } else {
            null
        }
    }

    /** The line for a remove that matched nothing anywhere, naming where it looked. */
    fun nothingFound(phrase: String, day: LocalDate?): String {
        val today = RelativeDay.today()
        val (from, to) = EventRemovalWords.window(day, today)
        val where = if (from == to) {
            "on ${EventRemovalWords.dayWords(from, today)}"
        } else {
            "from today through ${to.format(EventRemovalWords.SHORT_DAY)}"
        }
        return "Nothing was removed: no reminder, to-do or calendar entry $where matches \"$phrase\". " +
            "If it is on another day, ask with that day."
    }

    /**
     * Entries or reminders, by tier; his words ("the reminder", "the calendar one") set one pool
     * aside. Null when the reminder path should run, or nothing matched.
     */
    private suspend fun pick(
        dao: EventDao,
        events: TextMatches<Event>,
        reminders: TextMatches<ListItem>,
        userText: String?,
    ): JSONObject? {
        val named = EventRemovalWords.kindNamed(userText)
        val eventTier = if (named == EventRemovalWords.Kind.REMINDER) TextMatches.NONE else events.tier
        val reminderTier = if (named == EventRemovalWords.Kind.ENTRY) TextMatches.NONE else reminders.tier
        return when {
            eventTier == TextMatches.NONE || reminderTier < eventTier -> null
            reminderTier == eventTier -> whichOne(events.items, reminders.items.map { it.text })
            events.items.size > 1 -> whichOne(events.items, emptyList())
            else -> ask(dao, events.items.single(), "")
        }
    }

    /** His answer to the waiting question, or null when this call is a fresh request. */
    @Suppress("ReturnCount") // spent, a no, a row gone: each answers on its own line
    private suspend fun answer(context: Context, phrase: String, day: LocalDate?, userText: String?): JSONObject? {
        val ask = pending ?: return null
        // One answer per question, whatever it is.
        pending = null
        if (clock() - ask.atMs > TTL_MS || turn != ask.turn + 1) return null
        if (EventRemovalWords.isPlainNo(userText.orEmpty())) {
            return fail("Nothing was removed: his answer was no, so \"${ask.title}\" stays on the calendar.")
        }
        val dao = CarDatabase.getDatabase(context).eventDao()
        val row = dao.getById(ask.eventId)?.takeIf { !it.deleted && it.kind == EventKind.EVENT }
            ?: return fail("Nothing was removed: \"${ask.title}\" is no longer on the calendar.")
        val sameRow = tieredTextMatches(phrase, listOf(row)) { it.title }.tier <= TextMatches.SUBSTRING &&
            (day == null || EventRemovalWords.rowDay(row, RelativeDay.clock().zone) == day)
        val yes = EventRemovalWords.isBareYes(userText.orEmpty(), CompanionProfile.name(context))
        return if (sameRow && yes) confirm(context, dao, ask, row) else null
    }

    /** A plain yes to the same row: removed, unless it changed since the question or the clip was too quiet. */
    @Suppress("ReturnCount") // too quiet, changed: each asks again on its own line
    private suspend fun confirm(context: Context, dao: EventDao, ask: Ask, row: Event): JSONObject? {
        val voiced = kotlin.coroutines.coroutineContext[ClearCodesVoiceGate.HeardVoice]?.voicedMs
        if (voiced != null && voiced < ClearCodesVoiceGate.YES_MIN_VOICED_MS) return null
        if (row.title != ask.title || row.startsAt != ask.startsAt) return ask(dao, row, "It changed since I asked. ")
        val zone = RelativeDay.clock().zone
        val whenWords = whenPhrase(row, RelativeDay.today(), zone)
        return if (NotesController.removeAppointment(context, row.id)) {
            JSONObject().put("success", true).put("title", row.title)
                .put("message", "Removed \"${row.title}\" ($whenWords) from the calendar.")
        } else {
            fail("Something went wrong: \"${row.title}\" is still on the calendar.")
        }
    }

    private suspend fun ask(dao: EventDao, row: Event, lead: String): JSONObject {
        pending = Ask(row.id, row.title, row.startsAt, turn, clock())
        val others = dao.getActiveByKind(EventKind.EVENT)
            .count { it.id != row.id && it.title.trim().equals(row.title.trim(), ignoreCase = true) }
        val scope = when {
            row.repeatKind != null ->
                " It repeats, and the whole series goes with it: one occurrence cannot be removed on its own."
            others == 1 -> " Only this one goes; the other \"${row.title}\" entry stays."
            others > 1 -> " Only this one goes; the other $others \"${row.title}\" entries stay."
            else -> ""
        }
        val zone = RelativeDay.clock().zone
        return fail("${lead}Remove \"${row.title}\", ${whenPhrase(row, RelativeDay.today(), zone)}?$scope", asks = true)
            .put("instruction", ASK_AND_WAIT)
    }

    private fun whichOne(events: List<Event>, reminderNames: List<String>): JSONObject {
        pending = null
        val zone = RelativeDay.clock().zone
        val today = RelativeDay.today()
        val named = reminderNames.map { "the reminder \"$it\"" } +
            events.sortedBy { it.startsAt ?: 0L }.map { "\"${it.title}\", ${whenPhrase(it, today, zone)}" }
        val more = named.size - MAX_NAMED
        val tail = if (more > 0) "; and $more more: a day narrows it" else ""
        val listed = named.take(MAX_NAMED).joinToString("; ")
        val both = if (reminderNames.isEmpty()) {
            ""
        } else {
            " A reminder and a calendar entry match equally: say \"the reminder\" or \"the calendar one\"."
        }
        return fail("Nothing was removed. Which one? $listed$tail.$both", asks = true)
    }

    /** Nothing matched among events or reminders: coursework by that name gets its own answer. */
    private suspend fun taskOnly(dao: EventDao, phrase: String, fromMs: Long, toMs: Long): JSONObject? {
        val zone = RelativeDay.clock().zone
        val tasks = tieredTextMatches(phrase, dao.activeByKindInLocalWindow(EventKind.TASK, fromMs, toMs, zone)) {
            it.title
        }.items
        return tasks.firstOrNull()?.let {
            fail(
                "Nothing was removed: \"${it.title}\" is coursework, and coursework is not deleted, " +
                    "by voice or on the Calendar screen. It can be marked done.",
            )
        }
    }
}
