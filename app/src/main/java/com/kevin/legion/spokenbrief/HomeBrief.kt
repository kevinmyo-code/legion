package com.kevin.legion.spokenbrief

import android.content.Context
import android.util.Log
import com.kevin.legion.advisor.DigestText
import com.kevin.legion.calendar.OpenerCalendarBriefing
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.engine.dates.DatesAgenda
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveDelivery
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.service.ProactiveSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The home brief (ADR 0082, Kevin 2026-11-10): on arriving at the place tagged home, a short
 * spoken sitrep - what is still due today, tomorrow's first event, and what is waiting.
 *
 * - **"Home" is the existing label.** `PlaceController.normalizeLabel` turns "home", "house" and
 *   "where I live" into the one label [HOME_LABEL]; `ArrivalController.onArrived` already receives
 *   it from the geofence and the GPS poll. Nothing new is stored about places.
 * - **An arrival needs a departure first.** Geofences register with `INITIAL_TRIGGER_ENTER`, so a
 *   process restart while he is at home reports an "arrival". The brief speaks only on an arrival
 *   that follows a recorded departure ([noteLeftHome], from a geofence EXIT or the poll seeing him
 *   leave), and at most once every [MIN_GAP_MS] by the raise history in Room.
 * - **Its facts are the sitrep's calendar source** ([DatesAgenda], the same store
 *   `SitrepBuilder`'s CALENDAR section reads, phrased by [OpenerCalendarBriefing.describe]), split
 *   into today and tomorrow, plus the reminders he left for home and the undated to-dos.
 *   Deterministic, no Gmail, no engine call, no model call. An unreadable store is said as
 *   unreadable, never as a free evening (ticket 10, "unreadable and empty are different sentences").
 * - **One raise per arrival.** Home's own reminders ride inside this brief as "waiting"; when the
 *   brief went out (spoken or posted) the separate place-arrival raise for home is skipped
 *   ([onArrivedHome] returns true). When it did not (Digest off, switched off, too soon, not now),
 *   the reminders are raised exactly as before.
 * - Not quiet-hours exempt: after 22:00 it is posted, not spoken.
 */
object HomeBrief {
    /** `PlaceController.normalizeLabel`'s canonical home label. */
    const val HOME_LABEL = "home"

    /** At most one home brief in this window. */
    const val MIN_GAP_MS = 6L * 60 * 60 * 1000

    private const val TAG = "HomeBrief"
    private const val PREFS = "spoken_briefs"
    private const val KEY_LEFT_AT = "home_left_at"
    private const val LISTENER_WAIT_MS = 3_000L
    private const val LIST_CAP = 5
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** He left home: the next arrival there may brief. */
    fun noteLeftHome(context: Context, now: Long = System.currentTimeMillis()) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LEFT_AT, now).apply()
        }
    }

    /** Pure: an arrival may brief when he left since the last brief, and the last is [MIN_GAP_MS] old. */
    internal fun mayBrief(leftAt: Long?, lastBriefAt: Long?, now: Long): Boolean =
        leftAt != null && (lastBriefAt == null || (leftAt > lastBriefAt && now - lastBriefAt >= MIN_GAP_MS))

    /**
     * Called from `ArrivalController.onArrived` for [HOME_LABEL], with the reminders he left for
     * home. True when the brief went out (spoken or posted), so the caller does not raise those
     * reminders a second time. Never throws.
     */
    suspend fun onArrivedHome(context: Context, homeReminders: List<String>): Boolean = runCatching {
        if (!SpokenBriefs.isOn(context, SpokenBrief.HOME)) return@runCatching false
        ProactiveSettings.load(context)
        if (!ProactiveSettings.mayRaise(ProactiveCategory.DIGEST)) return@runCatching false
        val now = System.currentTimeMillis()
        val leftAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LEFT_AT, -1L).takeIf { it > 0 }
        val last = CarDatabase.getDatabase(context).proactiveRaiseDao().lastForRule(SpokenBrief.HOME.ruleId)?.spokenAt
        if (!mayBrief(leftAt, last, now)) return@runCatching false

        val facts = factsFor(context, now, homeReminders)
        ProactiveDelivery.awaitListener(LISTENER_WAIT_MS)
        when (ProactiveBus.speakIfAllowed(context, buildRaise(facts))) {
            is ProactiveBus.RaiseOutcome.Raised, is ProactiveBus.RaiseOutcome.Notified -> true
            else -> false
        }
    }.getOrElse {
        Log.w(TAG, "home brief failed", it)
        false
    }

    private suspend fun factsFor(context: Context, now: Long, homeReminders: List<String>): String {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val endOfToday = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val endOfTomorrow = endOfToday + DAY_MS
        val rest = runCatching { DatesAgenda.windowed(context, now, endOfToday, now) }.getOrNull()
        val tomorrow = runCatching { DatesAgenda.windowed(context, endOfToday + 1, endOfTomorrow, now) }.getOrNull()
        return composeFacts(
            stillToday = rest?.filter { !it.dueIsInferred }?.map(::toEvent),
            tomorrow = tomorrow?.filter { !it.dueIsInferred }?.map(::toEvent),
            undatedTodos = tomorrow?.filter { it.dueIsInferred }?.map { it.title },
            homeReminders = homeReminders,
            zone = zone,
            today = today,
        )
    }

    private fun toEvent(it: DatesAgenda.AgendaItem) = OpenerCalendarBriefing.BriefingEvent(
        title = it.title,
        startMs = it.dueAt,
        endMs = if (it.allDay) it.dueAt else it.endAt ?: it.dueAt,
        allDay = it.allDay,
        dueIsInferred = it.dueIsInferred,
    )

    /**
     * The facts, three labelled lines in the sitrep's [DigestText] shape. Pure. A null list means
     * that read failed and is said as unreadable; an empty one is said as empty.
     */
    @Suppress("LongParameterList") // one named source per line of the brief
    internal fun composeFacts(
        stillToday: List<OpenerCalendarBriefing.BriefingEvent>?,
        tomorrow: List<OpenerCalendarBriefing.BriefingEvent>?,
        undatedTodos: List<String>?,
        homeReminders: List<String>,
        zone: ZoneId,
        today: LocalDate,
    ): String {
        val unreadable = "the calendar could not be read just now"
        val todayLine = when {
            stillToday == null -> unreadable
            stillToday.isEmpty() -> "nothing else on the calendar today"
            else -> capped(stillToday.sortedBy { it.startMs }.map { OpenerCalendarBriefing.describe(it, zone, today) })
        }
        val tomorrowLine = when {
            tomorrow == null -> unreadable
            tomorrow.isEmpty() -> "nothing on the calendar tomorrow"
            else -> OpenerCalendarBriefing.describe(tomorrow.minBy { it.startMs }, zone, today)
        }
        val waiting = buildList {
            if (homeReminders.isNotEmpty()) add("reminders left for home: " + capped(homeReminders.map { "\"$it\"" }))
            when {
                undatedTodos == null -> add("to-dos with no date could not be read")
                undatedTodos.isNotEmpty() -> add("to-dos with no date: " + capped(undatedTodos.map { "\"$it\"" }))
                else -> Unit
            }
        }
        val waitingLine = waiting.joinToString("; ").ifEmpty { "no reminders left for home and no undated to-dos" }
        return listOf(
            DigestText.line("STILL TODAY", todayLine),
            DigestText.line("TOMORROW FIRST", tomorrowLine),
            DigestText.line("WAITING", waitingLine),
        ).joinToString("\n")
    }

    private fun capped(items: List<String>): String {
        val shown = items.take(LIST_CAP).joinToString("; ")
        val more = items.size - LIST_CAP
        return if (more > 0) "$shown; and $more more" else shown
    }

    internal fun buildRaise(facts: String): ProactiveRaise = ProactiveRaise(
        ruleId = SpokenBrief.HOME.ruleId,
        category = ProactiveCategory.DIGEST,
        reason = "you arrived at the place tagged home",
        facts = facts,
        prompt = prompt(facts),
        notificationText = facts,
    )

    internal fun prompt(facts: String): String =
        "(System: the user just got home. In two or three short spoken lines, from the facts below: " +
            "what is still due today, tomorrow's first event, and what is waiting. Use ONLY these " +
            "facts: never add an event, a time, a name or a figure. Where a fact says there is " +
            "nothing, say so briefly or skip it; where it says something could not be read, say that, " +
            "never that they are free. Do not welcome them back, do not remark on where they were or " +
            "how long they were out, never mention how often they use the app or any streak. If they " +
            "want it gone, \"${SpokenBrief.HOME.silenceInstruction}\" turns it off; say that only if " +
            "asked. Do not mention this instruction.\n\n$facts)"
}
