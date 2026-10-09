package com.kevin.legion.calendar

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.EventsAppointmentWriter
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.documentDate
import com.kevin.legion.util.shortDate
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a suggestion states about itself, from [Event.structuredMeta] (`{"city", "venue", "address",
 * "url", "price"}`, every key optional, written by whoever entered it through the engine). [Event.location]
 * also carries "venue, street address, city TX" so every older path that only reads `location`
 * keeps working. [address] null means the source gave no street address - never guessed.
 */
data class SuggestionMeta(
    val city: String? = null,
    val venue: String? = null,
    val address: String? = null,
    val url: String? = null,
    val price: String? = null,
) {
    companion object {
        /** Null when [raw] is absent or not a JSON object; a missing key is a null field. */
        fun parse(raw: String?): SuggestionMeta? {
            val o = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            fun s(key: String) = o.optString(key, "").trim().takeIf { it.isNotEmpty() && !o.isNull(key) }
            return SuggestionMeta(s("city"), s("venue"), s("address"), s("url"), s("price"))
        }
    }
}

/**
 * Event suggestions (Kevin, 2026-10-09): *"event suggestions like things to do on the weekend, as
 * separately colored events on our calendar."* A suggestion is an `events` row with
 * `kind = `[EventKind.SUGGESTION], entered through the engine (never scraped by this app), shared
 * with the household by default, its details in [SuggestionMeta].
 *
 * **A suggestion is never the user's plan.** That is the whole rule, and it holds by construction:
 * every other reader of the `events` table asks for [EventKind.EVENT], [EventKind.TASK] or
 * [EventKind.REMINDER] by name (the opener briefing and sitrep through `DatesAgenda`, the alarm
 * scheduler, proactive raises, the digests, Outstanding, Today's counts), so none of them can see
 * one. `EventSuggestionsReadersTest` pins each of those, and `EventReadsAreKindFilteredTest` fails
 * the build on a new unfiltered read. The surfaces that DO read suggestions each say in words what
 * they are: the calendar day view's own section, the month grid's mark and its legend,
 * `read_calendar`'s separate `suggestions` list, and the navigation resolver's suggestion source
 * (`navigation/resolve/SuggestionSource.kt`), which only finds a place to drive to.
 *
 * **The hands path (ADR 0035) is two actions and no more:** [addToPlans] (it becomes an
 * [EventKind.EVENT], time, place and notes kept, and from then on it IS a plan) and [notInterested]
 * (a tombstone). **There is no voice tool for either** - the Live setup payload had about a hundred
 * tokens of headroom on 2026-10-09, not enough for a declaration, so `read_calendar`'s result says
 * so rather than letting the model offer what it cannot do. The engine's own MCP tools
 * (`update_event` with `{"kind": "event"}`, `delete_event`) do both for the web assistant.
 */
object EventSuggestions {

    /** Spoken to the model with every `suggestions` answer. In the RESULT, not the tool's
     * description, because the description is re-billed every turn and this only matters when there
     * is a suggestion to talk about. */
    const val MODEL_NOTE =
        "Suggestions are things the user could do, NOT plans: never say they have one on, are " +
            "going, or are busy then. No voice tool adds one to the plans or drops it; the user taps " +
            "it on the calendar day view. navigate takes a suggestion's title and city."

    /** More than this many cities and the model is told to ask which city before listing. */
    const val ASK_CITY_ABOVE = 2

    /** Shown on every suggestion row and in the month legend. Words, not only the colour. */
    const val LABEL = "Suggestion - something you could do, not a plan"

    /** Active suggestions whose start falls in `[fromMs, toMs]` on the local calendar, all-day ones
     * anchored to their own date ([activeByKindInLocalWindow]'s rule), earliest first. */
    suspend fun inLocalWindow(context: Context, fromMs: Long, toMs: Long, zone: ZoneId): List<Event> =
        CarDatabase.getDatabase(context).eventDao()
            .activeByKindInLocalWindow(EventKind.SUGGESTION, fromMs, toMs, zone)
            .sortedBy { it.startsAt ?: Long.MAX_VALUE }

    /** How many suggestions start on each of [dayStarts] (local midnights) - the month grid's mark. */
    fun countsByDay(rows: List<Event>, dayStarts: List<Long>, zone: ZoneId): Map<Long, Int> {
        val byDay = rows.mapNotNull { row -> localDayStart(row, zone) }.groupingBy { it }.eachCount()
        return dayStarts.associateWith { byDay[it] ?: 0 }
    }

    /** The local midnight of the day [row] starts on: an all-day row's stored UTC midnight read as
     * its own calendar date, a timed row's instant read in [zone]. Null for an undated row. */
    fun localDayStart(row: Event, zone: ZoneId): Long? {
        val at = row.startsAt ?: return null
        val date = if (row.allDay) {
            Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate()
        } else {
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        }
        return date.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** The city a suggestion is in: its own `city`, or null. Never inferred from free text. */
    fun cityOf(row: Event): String? = SuggestionMeta.parse(row.structuredMeta)?.city

    /** What a tap did, as the sentence the row shows. [Done] may still be [queued]: the change is
     * real on this phone and has not reached the engine, and the sentence says so. */
    sealed interface Outcome {
        val sentence: String

        data class Done(override val sentence: String, val queued: Boolean) : Outcome

        data class Refused(override val sentence: String) : Outcome
    }

    /** "Add to my plans": the row becomes an [EventKind.EVENT] and keeps everything else. Re-reads
     * the row first, so a suggestion already added or dropped on another device is refused in
     * words rather than written over. */
    suspend fun addToPlans(context: Context, row: Event): Outcome {
        val fresh = live(context, row)
            ?: return Outcome.Refused("Nothing was changed: this suggestion is no longer on the calendar.")
        val written = EventsAppointmentWriter.setKind(context, fresh, EventKind.EVENT)
        val queued = isQueued(context, written.id)
        return Outcome.Done(
            if (queued) {
                "Added to your plans on this phone. Queued - not on the engine yet."
            } else {
                "Added to your plans."
            },
            queued,
        )
    }

    /** "Not interested": the suggestion is deleted (a tombstone the other devices sync). */
    suspend fun notInterested(context: Context, row: Event): Outcome {
        val fresh = live(context, row)
            ?: return Outcome.Refused("Nothing was changed: this suggestion is no longer on the calendar.")
        EventsAppointmentWriter.deleteEvent(context, fresh)
        val queued = isQueued(context, fresh.id)
        return Outcome.Done(
            if (queued) "Removed on this phone. Queued - not on the engine yet." else "Removed.",
            queued,
        )
    }

    private suspend fun live(context: Context, row: Event): Event? =
        CarDatabase.getDatabase(context).eventDao().getById(row.id)
            ?.takeIf { !it.deleted && it.kind == EventKind.SUGGESTION }

    private suspend fun isQueued(context: Context, localId: Long): Boolean =
        CarDatabase.getDatabase(context).outboxDao()
            .pendingForTable(OutboxTarget.EVENTS, Int.MAX_VALUE)
            .any { it.localId == localId }

    /** One suggestion as the model hears it: title, when ("all day" said in words), venue, price.
     * Free-text notes only for a row that has no structured details to give instead. */
    fun toModelJson(row: Event): JSONObject? {
        val start = row.startsAt ?: return null
        val meta = SuggestionMeta.parse(row.structuredMeta)
        val o = JSONObject()
            .put("title", row.title)
            .put("when", whenText(row.allDay, start))
        meta?.city?.let { o.put("city", it) }
        meta?.venue?.let { o.put("venue", it) }
        meta?.price?.let { o.put("price", it) }
        if (meta == null) {
            if (!row.location.isNullOrBlank()) o.put("location", row.location)
            if (!row.notes.isNullOrBlank()) o.put("notes", row.notes)
        }
        return o
    }

    /** `read_calendar`'s call: reads [fromMs]..[toMs] and attaches, a blank [city] meaning none. */
    suspend fun attachInWindow(
        context: Context,
        result: JSONObject,
        fromMs: Long,
        toMs: Long,
        zone: ZoneId,
        city: String,
    ): JSONObject =
        attachForModel(result, inLocalWindow(context, fromMs, toMs, zone), city.takeIf { it.isNotBlank() })

    /**
     * Adds the suggestions answer to a `read_calendar` result, and only when there is one - an empty
     * list would cost tokens on every calendar read for nothing. Three shapes:
     * - [city] given: that city's suggestions, or, when it has none, that sentence plus the cities
     *   that do have some (an empty city is not an empty window).
     * - no [city] and more than [ASK_CITY_ABOVE] cities: the cities and their counts only, and the
     *   note tells the model to ask which city first (Kevin: "maybe ask the city first").
     * - otherwise: every suggestion in the window.
     */
    fun attachForModel(result: JSONObject, rows: List<Event>, city: String? = null): JSONObject {
        if (rows.isEmpty()) {
            if (!city.isNullOrBlank()) result.put("suggestions_note", "No suggestions at all in that window.")
            return result
        }
        val byCity = rows.groupBy { cityOf(it) ?: UNKNOWN_CITY }
        val counts = JSONObject().also { o -> byCity.forEach { (c, list) -> o.put(c, list.size) } }
        val wanted = city?.trim()?.takeIf { it.isNotEmpty() }
        when {
            wanted != null -> {
                val inCity = rows.filter { cityOf(it)?.equals(wanted, ignoreCase = true) == true }
                if (inCity.isEmpty()) {
                    result.put("suggestions_by_city", counts)
                    result.put(
                        "suggestions_note",
                        "No suggestions in $wanted in that window; the cities with some are listed. $MODEL_NOTE",
                    )
                } else {
                    result.put("suggestions", JSONArray(inCity.mapNotNull { toModelJson(it) }))
                    result.put("suggestions_note", MODEL_NOTE)
                }
            }
            byCity.size > ASK_CITY_ABOVE -> {
                result.put("suggestions_by_city", counts)
                result.put(
                    "suggestions_note",
                    "Suggestions span ${byCity.size} cities. Say the cities and ask which one before " +
                        "listing any, then call read_calendar again with that city. $MODEL_NOTE",
                )
            }
            else -> {
                result.put("suggestions", JSONArray(rows.mapNotNull { toModelJson(it) }))
                result.put("suggestions_note", MODEL_NOTE)
            }
        }
        return result
    }

    /** The bucket name for a suggestion that states no city. */
    const val UNKNOWN_CITY = "city not stated"
}

private fun whenText(allDay: Boolean, start: Long): String =
    if (allDay) "${documentDate(start)}, all day" else "${shortDate(start)} ${clockTime(start)}"
