package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val EVENTS_TABLE = "events"
private const val EVENT_SKIPS_TABLE = "event_skips"

private fun tsOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }
private fun parseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
private fun parseTsOrNull(s: String?): Long? = s?.let { parseTs(it) }
private fun dateOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
private fun parseDateOrNull(s: String?): Long? = s?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }

/**
 * The wire shape sent by [SupabaseEventsBackend.upsert] and [SupabaseEventsBackend.uploadMigratedEvent] -
 * one row worth of every writable column on public.events. Every nullable property here is
 * deliberately REQUIRED (no "= null" default), same trick PlaceUpsertDto.deletedAt's own doc
 * comment explains at length: kotlinx-serialization's encodeDefaults = false omits a property
 * equal to its declared default, and null is still a default, so an un-set nullable field would
 * silently vanish from the outgoing JSON and a genuine clear-to-null (unticking a done item,
 * clearing a trigger, removing a repeat rule) would leave the OLD value sitting server-side
 * untouched. Forcing every field onto the wire on every write reproduces whole-row-replace
 * semantics, matching EventFields's own "every writable column, always" contract.
 *
 * **`created_at` is the one deliberate exception** - see that field's own doc comment just below
 * for why a `NOT NULL` column needs the opposite defaulting rule from everything else here.
 */
@Serializable
private data class EventUpsertDto(
    val title: String,
    // The ONE deliberate exception to this class's own "always on the wire" rule stated above.
    // `created_at` is `timestamptz NOT NULL default now()` - unlike every other nullable column
    // here, a null value is not a legal write, so this field CANNOT follow the no-default
    // convention: a required `String?` with no default is still serialized as a literal JSON
    // `null` by kotlinx-serialization (only a DEFAULTED property gets dropped when
    // encodeDefaults = false, and null counts as its own default), which would send `"created_at":
    // null` straight into a NOT NULL column and fail the write outright - "hoping" it would be
    // dropped, rather than checking, is exactly the mistake this default exists to prevent.
    // Giving it `= null` means EventFields.createdAtMs == null (an ordinary live create with no
    // known prior creation time) OMITS the key entirely, so Postgres's own `default now()` decides
    // on INSERT and an UPDATE leaves the existing value untouched - both the correct behaviour.
    // EventsReconcile.uploadMigratedEvent always supplies a real value here (the originating
    // engine record's own createdAt), so a migrated row's `created_at` is never left to default.
    @SerialName("created_at") val createdAt: String? = null,
    // Nullable (backend-erp ticket 07, RULED 2026-08-26 option 1): a genuinely dateless Notes
    // Item is an ordinary row now, never a guessed date. Deliberately no "= null" default -
    // see this file's own class doc for why every writable column must always be present on
    // the wire, a clear-to-null included.
    @SerialName("starts_at") val startsAt: String?,
    @SerialName("ends_at") val endsAt: String?,
    @SerialName("all_day") val allDay: Boolean,
    val location: String?,
    val notes: String?,
    // JsonElement, never a plain String - EventFields.structuredMeta is JSON TEXT
    // (CalendarImportController.buildFieldValues produces it via org.json.JSONObject(...).toString()),
    // and serializing a Kotlin String property sends it to Postgrest as a quoted, backslash-escaped
    // JSON STRING SCALAR, so `structured_meta` would end up holding `"{\"course\":\"COSC4320\"}"` -
    // a jsonb value that IS a string, not the JSON OBJECT this migration's own doc comment promises
    // ("Postgres can index and query it"). EventUpsertDto.from parses the text back into a real
    // JsonElement before it ever reaches this DTO, same pattern SupabasePantryBackend.commitReceipt
    // already uses for exactly this reason (that function's own doc comment). Nullable with no
    // "= null" default, same rule as every other clearable column in this class - an event whose
    // block was removed on a re-import must actually clear the server's value, not silently leave
    // the old block sitting there.
    @SerialName("structured_meta") val structuredMeta: JsonElement?,
    val source: String,
    @SerialName("google_event_id") val googleEventId: String?,
    val done: Boolean,
    @SerialName("done_at") val doneAt: String?,
    @SerialName("sort_order") val sortOrder: Int?,
    @SerialName("trigger_place_label") val triggerPlaceLabel: String?,
    @SerialName("repeat_kind") val repeatKind: String?,
    @SerialName("repeat_every") val repeatEvery: Int?,
    @SerialName("repeat_days_of_week") val repeatDaysOfWeek: String?,
    @SerialName("repeat_day") val repeatDay: Int?,
    @SerialName("repeat_month") val repeatMonth: Int?,
    @SerialName("repeat_end_kind") val repeatEndKind: String?,
    @SerialName("repeat_end_date") val repeatEndDate: String?,
    @SerialName("repeat_end_count") val repeatEndCount: Int?,
    val exact: Boolean,
    @SerialName("exact_downgraded") val exactDowngraded: Boolean,
    @SerialName("missed_at") val missedAt: String?,
    @SerialName("missed_dismissed_at") val missedDismissedAt: String?,
    @SerialName("logged_at") val loggedAt: String?,
    @SerialName("origin_guid") val originGuid: String?,
) {
    companion object {
        fun from(fields: EventFields, originGuid: String? = null) = EventUpsertDto(
            title = fields.title,
            createdAt = tsOrNull(fields.createdAtMs),
            startsAt = tsOrNull(fields.startsAtMs),
            endsAt = tsOrNull(fields.endsAtMs),
            allDay = fields.allDay,
            location = fields.location,
            notes = fields.notes,
            // See this class's own field doc comment - parsed here, once, rather than trusting
            // the caller's JSON text is already a JsonElement. fields.structuredMeta is internal
            // (CalendarImportController's own org.json output), never user-typed free text, so a
            // parse failure here is a real bug in this codebase, not a malformed third-party
            // input - letting it throw (caught by the surrounding `translating` block, same as
            // SupabasePantryBackend.commitReceipt's identical parse) is correct.
            structuredMeta = fields.structuredMeta?.let { Json.parseToJsonElement(it) },
            source = fields.source,
            googleEventId = fields.googleEventId,
            done = fields.done,
            doneAt = tsOrNull(fields.doneAtMs),
            sortOrder = fields.sortOrder,
            triggerPlaceLabel = fields.triggerPlaceLabel,
            repeatKind = fields.repeatKind,
            repeatEvery = fields.repeatEvery,
            repeatDaysOfWeek = fields.repeatDaysOfWeek,
            repeatDay = fields.repeatDay,
            repeatMonth = fields.repeatMonth,
            repeatEndKind = fields.repeatEndKind,
            repeatEndDate = dateOrNull(fields.repeatEndDateMs),
            repeatEndCount = fields.repeatEndCount,
            exact = fields.exact,
            exactDowngraded = fields.exactDowngraded,
            missedAt = tsOrNull(fields.missedAtMs),
            missedDismissedAt = tsOrNull(fields.missedDismissedAtMs),
            loggedAt = tsOrNull(fields.loggedAtMs),
            originGuid = originGuid,
        )
    }
}

/** The wire shape read back off public.events for every operation. */
@Serializable
private data class EventRowDto(
    val id: String,
    val title: String,
    @SerialName("created_at") val createdAt: String,
    // Nullable, mirroring EventUpsertDto.startsAt's own comment - a genuinely dateless Notes
    // Item round-trips as a null starts_at, never a guessed one.
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    // JsonElement, mirroring EventUpsertDto.structuredMeta's own doc comment - decoded from a real
    // jsonb value server-side, converted to a compact JSON string only at the RemoteEvent boundary
    // (toRemoteEvent, below), never earlier.
    @SerialName("structured_meta") val structuredMeta: JsonElement? = null,
    val source: String,
    @SerialName("google_event_id") val googleEventId: String? = null,
    val done: Boolean = false,
    @SerialName("done_at") val doneAt: String? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
    @SerialName("trigger_place_label") val triggerPlaceLabel: String? = null,
    @SerialName("repeat_kind") val repeatKind: String? = null,
    @SerialName("repeat_every") val repeatEvery: Int? = null,
    @SerialName("repeat_days_of_week") val repeatDaysOfWeek: String? = null,
    @SerialName("repeat_day") val repeatDay: Int? = null,
    @SerialName("repeat_month") val repeatMonth: Int? = null,
    @SerialName("repeat_end_kind") val repeatEndKind: String? = null,
    @SerialName("repeat_end_date") val repeatEndDate: String? = null,
    @SerialName("repeat_end_count") val repeatEndCount: Int? = null,
    val exact: Boolean = false,
    @SerialName("exact_downgraded") val exactDowngraded: Boolean = false,
    @SerialName("missed_at") val missedAt: String? = null,
    @SerialName("missed_dismissed_at") val missedDismissedAt: String? = null,
    @SerialName("logged_at") val loggedAt: String? = null,
    @SerialName("origin_guid") val originGuid: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteEvent() = RemoteEvent(
        serverId = id,
        title = title,
        createdAtMs = parseTs(createdAt),
        startsAtMs = parseTsOrNull(startsAt),
        endsAtMs = parseTsOrNull(endsAt),
        allDay = allDay,
        location = location,
        notes = notes,
        // .toString() on a JsonElement is kotlinx-serialization's own compact-JSON rendering, the
        // exact inverse of EventUpsertDto.from's Json.parseToJsonElement - round-trips key order
        // and values without this file re-implementing JSON formatting by hand.
        structuredMeta = structuredMeta?.toString(),
        source = source,
        googleEventId = googleEventId,
        done = done,
        doneAtMs = parseTsOrNull(doneAt),
        sortOrder = sortOrder,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDateMs = parseDateOrNull(repeatEndDate),
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAtMs = parseTsOrNull(missedAt),
        missedDismissedAtMs = parseTsOrNull(missedDismissedAt),
        loggedAtMs = parseTsOrNull(loggedAt),
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

/** The wire shape for a soft-delete PATCH - a genuine partial update, touching only the tombstone
 * column, same shape as PlaceDeleteDto. */
@Serializable
private data class EventDeleteDto(
    @SerialName("deleted_at") val deletedAt: String,
)

/** The wire shape for one public.event_skips row. */
@Serializable
private data class EventSkipRowDto(
    @SerialName("event_id") val eventId: String,
    @SerialName("skip_date") val skipDate: String,
)

/**
 * EventsBackend's real implementation over Postgrest, against public.events and
 * public.event_skips (supabase/migrations/20260825000400_aspect_dates_notes_merged.sql). This
 * is the deliberately untested seam in the events cutover, same posture as
 * SupabasePlacesBackend/SupabasePantryBackend - exercising it for real needs a live project.
 * EventsBackend is the fake-friendly interface; every branch here does nothing but translate
 * exceptions and decode DTOs.
 */
class SupabaseEventsBackend(private val client: SupabaseClient) : EventsBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(EventsBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(EventsBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(EventsBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    override suspend fun fetchActive(): Result<List<RemoteEvent>> = translating("load your dates and notes") {
        client.postgrest.from(EVENTS_TABLE)
            .select {
                filter { filter("deleted_at", FilterOperator.IS, "null") }
            }
            .decodeList<EventRowDto>()
            .map { it.toRemoteEvent() }
    }

    override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> =
        translating(if (serverId == null) "save that" else "update that") {
            val dto = EventUpsertDto.from(fields)
            if (serverId == null) {
                client.postgrest.from(EVENTS_TABLE)
                    .insert(dto) { select() }
                    .decodeSingle<EventRowDto>()
                    .toRemoteEvent()
            } else {
                client.postgrest.from(EVENTS_TABLE)
                    .update(dto) {
                        select()
                        filter { eq("id", serverId) }
                    }
                    .decodeSingle<EventRowDto>()
                    .toRemoteEvent()
            }
        }

    override suspend fun softDelete(serverId: String): Result<Boolean> = translating("remove that") {
        client.postgrest.from(EVENTS_TABLE)
            .update(EventDeleteDto(deletedAt = OffsetDateTime.now().toString())) {
                select()
                filter {
                    eq("id", serverId)
                    filter("deleted_at", FilterOperator.IS, "null")
                }
            }
            .decodeList<EventRowDto>()
            .isNotEmpty()
    }

    override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> =
        translating("skip that occurrence") {
            client.postgrest.from(EVENT_SKIPS_TABLE)
                .upsert(
                    EventSkipRowDto(eventId = serverId, skipDate = dateOrNull(skipDateEpochMs)!!),
                ) {
                    onConflict = "event_id,skip_date"
                }
            Unit
        }

    override suspend fun fetchSkips(serverId: String): Result<List<Long>> = translating("load skipped occurrences") {
        client.postgrest.from(EVENT_SKIPS_TABLE)
            .select {
                filter { eq("event_id", serverId) }
            }
            .decodeList<EventSkipRowDto>()
            .mapNotNull { parseDateOrNull(it.skipDate) }
    }

    /**
     * Selects by MigratedEvent.originGuid first, same "check before insert" shape as
     * SupabasePantryBackend.uploadMigratedReceipt - but here it is a re-run guard, not a
     * gate-immutability workaround (events are freely updatable, so a genuine second write would
     * be legal; it would just no longer be a MIGRATION, it would be an edit, and this function's
     * only job is the one-time transfer). Result.success(false) on an existing row skips the
     * skips upload too, since the event's server id (and therefore the skips' foreign key) did not
     * change.
     */
    override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> =
        translating("upload a migrated date or note") {
            val existing = client.postgrest.from(EVENTS_TABLE)
                .select {
                    filter { eq("origin_guid", event.originGuid) }
                }
                .decodeList<EventRowDto>()
            if (existing.isNotEmpty()) return@translating false

            val inserted = client.postgrest.from(EVENTS_TABLE)
                .insert(EventUpsertDto.from(event.fields, originGuid = event.originGuid)) { select() }
                .decodeSingle<EventRowDto>()

            if (event.skipDatesEpochMs.isNotEmpty()) {
                client.postgrest.from(EVENT_SKIPS_TABLE).insert(
                    event.skipDatesEpochMs.map { skipMs ->
                        EventSkipRowDto(eventId = inserted.id, skipDate = dateOrNull(skipMs)!!)
                    },
                )
            }
            true
        }
}
