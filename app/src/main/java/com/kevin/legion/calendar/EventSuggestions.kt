package com.kevin.legion.calendar

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.EventsAppointmentWriter
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.documentDate
import com.kevin.legion.util.shortDate
import java.net.URI
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

        /**
         * [raw] as a page the browser may open (Kevin, 2026-10-09: *"tap the event row to open up the
         * event's page in the browser"*), or null. Only an absolute `http`/`https` URL with a host
         * passes; null, blank, `javascript:`, `file:`, `intent:` and anything unparseable are refused,
         * so a row with no such URL is simply not tappable. The web's guard
         * (`server/frontend/src/lib/suggestion.ts`) checks the scheme only; this one also wants a host.
         */
        fun openablePageUrl(raw: String?): String? {
            val url = raw?.trim()?.takeIf { it.isNotEmpty() }
            val uri = url?.let { runCatching { URI(it) }.getOrNull() }
            val web = uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrBlank()
            return url.takeIf { web }
        }
    }
}

/**
 * One household member's "I want to go" on a suggestion (Kevin, 2026-10-09), as the engine's
 * read-only `pinned_by` array carries it and [Event.pinnedByJson] stores it. Oldest pin first.
 */
data class SuggestionPin(val userId: String, val displayName: String) {
    companion object {
        /** What [words] and `wants_to_go` call the phone's own user. */
        const val SELF = "You"

        /** [raw] as a list; null, blank or malformed text is an empty list, never a crash, and an
         * entry without a non-blank `user_id` and `display_name` is skipped rather than guessed. */
        fun parse(raw: String?): List<SuggestionPin> {
            val array = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONArray(it) }.getOrNull() }
                ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = if (o.isNull("user_id")) "" else o.optString("user_id", "").trim()
                val name = if (o.isNull("display_name")) "" else o.optString("display_name", "").trim()
                if (id.isEmpty() || name.isEmpty()) null else SuggestionPin(id, name)
            }
        }

        /** [pins] in the same shape the engine sends. */
        fun toJson(pins: List<SuggestionPin>): String = JSONArray().also { array ->
            pins.forEach { array.put(JSONObject().put("user_id", it.userId).put("display_name", it.displayName)) }
        }.toString()

        /**
         * [raw] with [userId]'s own pin added ([pinned] true; a pin already there is kept where it
         * is) or removed - the optimistic local change before the engine has answered. Everyone
         * else's pins are untouched.
         */
        fun withMine(raw: String?, userId: String, pinned: Boolean): String {
            val all = parse(raw)
            val next = when {
                !pinned -> all.filter { it.userId != userId }
                all.any { it.userId == userId } -> all
                else -> all + SuggestionPin(userId, SELF)
            }
            return toJson(next)
        }

        /** True when [myUserId] is among [pins]; false for a null id. */
        fun includes(pins: List<SuggestionPin>, myUserId: String?): Boolean =
            myUserId != null && pins.any { it.userId == myUserId }

        /**
         * Who wants to go, in words (never colour alone), mirrored by the web: "You want to go",
         * "Mia wants to go", "You and Mia want to go", "You, Mia and Sam want to go". The phone's
         * own user is "You" and always first; everyone else follows in [pins] order. Null when
         * nobody has pinned it.
         */
        fun words(pins: List<SuggestionPin>, myUserId: String?): String? {
            val names = names(pins, myUserId, SELF)
            if (names.isEmpty()) return null
            val joined = when (names.size) {
                1 -> names[0]
                else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
            }
            val verb = if (names.size == 1 && names[0] != SELF) "wants" else "want"
            return "$joined $verb to go"
        }

        /** Display names with the phone's own user first and called [selfName]. */
        fun names(pins: List<SuggestionPin>, myUserId: String?, selfName: String): List<String> {
            val mine = includes(pins, myUserId)
            val others = pins.filter { myUserId == null || it.userId != myUserId }.map { it.displayName }
            return (if (mine) listOf(selfName) else emptyList()) + others
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
 * **The hands path (ADR 0035) is three actions** - the third, the pin toggle, is
 * [SuggestionPinActions]; before it (2026-10-09) this said "two actions and no more". The first two:
 * [addToPlans] (it becomes an
 * [EventKind.EVENT], time, place and notes kept, and from then on it IS a plan) and [notInterested]
 * (a tombstone). **There is no voice tool for any of them** - the Live setup payload had about a hundred
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
            "it on the calendar day view. navigate takes a suggestion's title and city. wants_to_go " +
            "names household members who want to go; it is a wish, not a plan."

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

    internal suspend fun live(context: Context, row: Event): Event? =
        CarDatabase.getDatabase(context).eventDao().getById(row.id)
            ?.takeIf { !it.deleted && it.kind == EventKind.SUGGESTION }

    private suspend fun isQueued(context: Context, localId: Long): Boolean =
        CarDatabase.getDatabase(context).outboxDao()
            .pendingForTable(OutboxTarget.EVENTS, Int.MAX_VALUE)
            .any { it.localId == localId }

    /** One suggestion as the model hears it: title, when ("all day" said in words), venue, price.
     * Free-text notes only for a row that has no structured details to give instead. */
    fun toModelJson(row: Event, myUserId: String? = null): JSONObject? {
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
        // Who wants to go (pins, 2026-10-09): the phone's own user as "you", omitted when nobody has.
        SuggestionPin.names(SuggestionPin.parse(row.pinnedByJson), myUserId, "you").takeIf { it.isNotEmpty() }
            ?.let { o.put("wants_to_go", JSONArray(it)) }
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
        attachForModel(
            result,
            inLocalWindow(context, fromMs, toMs, zone),
            city.takeIf { it.isNotBlank() },
            EngineConfig(context.applicationContext).userId(),
        )

    /**
     * Adds the suggestions answer to a `read_calendar` result, and only when there is one - an empty
     * list would cost tokens on every calendar read for nothing. Three shapes:
     * - [city] given: that city's suggestions, or, when it has none, that sentence plus the cities
     *   that do have some (an empty city is not an empty window).
     * - no [city] and more than [ASK_CITY_ABOVE] cities: the cities and their counts only, and the
     *   note tells the model to ask which city first (Kevin: "maybe ask the city first").
     * - otherwise: every suggestion in the window.
     */
    fun attachForModel(
        result: JSONObject,
        rows: List<Event>,
        city: String? = null,
        myUserId: String? = null,
    ): JSONObject {
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
                    result.put("suggestions", JSONArray(inCity.mapNotNull { toModelJson(it, myUserId) }))
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
                result.put("suggestions", JSONArray(rows.mapNotNull { toModelJson(it, myUserId) }))
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
