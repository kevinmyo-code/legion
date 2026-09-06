package com.kevin.legion.backend.engine

import com.kevin.legion.backend.EventFields
import com.kevin.legion.backend.EventsBackend
import com.kevin.legion.backend.MigratedEvent
import com.kevin.legion.backend.RemoteEvent
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val EVENTS_PATH = "/api/events"

private val djangoJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

private fun tsOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }
private fun parseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
private fun parseTsOrNull(s: String?): Long? = s?.let { parseTs(it) }
private fun dateOrNull(ms: Long?): String? =
    ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
private fun parseDateOrNull(s: String?): Long? =
    s?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }

/**
 * One `public.events` row exactly as `api/events.py`'s `EventSerializer` renders it - **measured
 * against the live engine on 2026-09-06**, not inferred from the Python: every field name, and the
 * `Z`-suffixed microsecond timestamp format (`"2026-08-27T00:35:25.611063Z"`, which
 * [OffsetDateTime.parse] reads as ISO_OFFSET_DATE_TIME without help), came out of a real
 * `GET /api/events` against 192.168.1.117:8000. `EventsApiFixtures.kt` holds three of those rows
 * verbatim as the test fixture.
 *
 * **A deliberate copy of `SupabaseEventsBackend`'s own private `EventRowDto`, not a shared type.**
 * The two really do describe the same physical table, but that DTO is private to its own file and
 * this backend must not reach into a Supabase file's internals to work - the same "never depend on
 * another aspect's internals, copy the shape and say so" posture
 * [com.kevin.legion.backend.LastAspectsMerge]'s own class doc takes toward `LedgerConfigMerge`.
 * Two differences from the Supabase copy, both real rather than cosmetic: DRF also emits
 * `provenance` (dropped here by `ignoreUnknownKeys`, since [RemoteEvent] has no such field), and
 * `all_day`/`done`/`exact`/`exact_downgraded`/`kind` are always present in a DRF response rather
 * than being defaulted client-side - the defaults below are kept anyway so a hand-written fixture
 * or a future partial projection cannot fail to decode.
 */
@Serializable
private data class DjangoEventRow(
    val id: String,
    val title: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    // jsonb server-side, so DRF hands back a real JSON object - kept as a JsonElement here and
    // rendered to a compact JSON string only at the RemoteEvent boundary, exactly as the Supabase
    // DTO does (that file's own field comment explains why a Kotlin String property here would
    // round-trip the value as a quoted JSON string scalar instead of an object).
    @SerialName("structured_meta") val structuredMeta: JsonElement? = null,
    val source: String,
    val kind: String = "reminder",
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
        // All-day rows keep the UTC-midnight convention untouched: `starts_at` is a timestamptz on
        // both sides and this parses it as the instant it states, exactly as the Supabase path
        // does. A measured all-day row from the live engine reads `"2026-09-28T00:00:00Z"` and
        // comes through here as that same midnight-UTC instant - nothing re-derives a local
        // midnight from it, which is what would silently shift such a row by a day.
        startsAtMs = parseTsOrNull(startsAt),
        endsAtMs = parseTsOrNull(endsAt),
        allDay = allDay,
        location = location,
        notes = notes,
        structuredMeta = structuredMeta?.toString(),
        source = source,
        kind = kind,
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

/** `GET /api/events`'s envelope - `{"results": [...], "next": <iso or null>}`
 * (`api/sync.paginate_since`). [next] is the last row's `updated_at` when a FULL page came back,
 * null when this was the last page. */
@Serializable
private data class DjangoEventPage(
    val results: List<DjangoEventRow> = emptyList(),
    val next: String? = null,
)

/**
 * The write body for `POST /api/events` and `PATCH /api/events/<id>`. Every nullable property is
 * deliberately REQUIRED (no `= null` default) and `explicitNulls = true` is set on [djangoJson],
 * for the identical reason `SupabaseEventsBackend`'s own `EventUpsertDto` doc comment spells out:
 * an omitted key on a DRF `partial=True` PATCH leaves the OLD value in place, so a genuine
 * clear-to-null (unticking, clearing a trigger, removing a repeat rule) would silently not happen.
 * Forcing every writable column onto the wire on every write reproduces the whole-row-replace
 * semantics [EventFields] promises.
 *
 * **`created_at` is absent from this class, and that is a real, stated fidelity gap - not an
 * oversight.** `api/events.py` lists `created_at` in `EventSerializer.Meta.read_only_fields` and
 * `EventSerializer.create` stamps it from the server's own clock, so DRF DISCARDS any value a
 * client sends. Sending [EventFields.createdAtMs] anyway would put a field on the wire that reads,
 * to anyone maintaining this file, as though it were honoured. The consequence, stated plainly: an
 * appointment created offline on Monday and drained on Friday gets `created_at = Friday`
 * server-side, and the next pull's own merge copies that back over the local Monday. The Supabase
 * path does not have this gap (its `created_at` is writable); the migration path that most cares
 * about it, [com.kevin.legion.backend.EventsReconcile], is not routed through this backend at all.
 */
@Serializable
private data class DjangoEventWrite(
    val title: String,
    @SerialName("starts_at") val startsAt: String?,
    @SerialName("ends_at") val endsAt: String?,
    @SerialName("all_day") val allDay: Boolean,
    val location: String?,
    val notes: String?,
    @SerialName("structured_meta") val structuredMeta: JsonElement?,
    val source: String,
    val kind: String,
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
        fun from(fields: EventFields, originGuid: String?) = DjangoEventWrite(
            title = fields.title,
            startsAt = tsOrNull(fields.startsAtMs),
            endsAt = tsOrNull(fields.endsAtMs),
            allDay = fields.allDay,
            location = fields.location,
            notes = fields.notes,
            // Parsed once, here, rather than trusting the caller's text is already a JsonElement -
            // fields.structuredMeta is this codebase's own org.json output, never user-typed free
            // text, so a parse failure is a real bug and letting it throw (into the surrounding
            // `translating` block) is correct. Same call the Supabase DTO makes.
            structuredMeta = fields.structuredMeta?.let { Json.parseToJsonElement(it) },
            source = fields.source,
            kind = fields.kind,
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

/**
 * [EventsBackend] over the household Django engine (`server/api/events.py`, `server/api/urls.py`) -
 * the Django half of the two transports that coexist during the port
 * (`.scratch/django-engine/research/execution-plan.md` Phase 2). Interchangeable with
 * [com.kevin.legion.backend.SupabaseEventsBackend] by construction: same interface, same
 * [RemoteEvent] shape out, so [com.kevin.legion.backend.EventsSync.pull]'s merge rules and its
 * tests are untouched by which one is handed in.
 *
 * **Two capabilities this transport genuinely does not have, and both FAIL rather than pretend.**
 * `server/api/urls.py` routes `events`, `events/<id>` and `changes` - there is no `event_skips`
 * endpoint at all - so [skipOccurrence] and [fetchSkips] return `Result.failure` with a sentence
 * naming what is missing. **[fetchSkips] deliberately does not return an empty list**: an empty
 * list is the same value as "this event has no skipped occurrences", and rendering "the engine
 * cannot answer" as "nothing is skipped" is the unreadable-vs-empty conflation CLAUDE.md section 1
 * names by name. [uploadMigratedEvent] refuses BEFORE it writes anything when the caller attached
 * skip dates, so a partial upload (the event row landed, its skips did not) is impossible rather
 * than merely unlikely.
 */
class DjangoEventsBackend(private val http: EngineHttp) : EventsBackend {

    // translating/prefix/decode used to be private members here and byte-identical copies in
    // DjangoChecklistsBackend; they now live once in EngineCalls.kt - see that file's own header
    // for the second reason that move was required (detekt's per-class function ceiling).
    private fun <T> decode(serializer: KSerializer<T>, body: String): T =
        djangoJson.decodeFromString(serializer, body)

    /**
     * Every page of `GET /api/events?since=<iso>`, followed to the end.
     *
     * **The loop is bounded three ways and cannot spin.** `api/sync.paginate_since` hands back
     * `next` = the LAST row's own `updated_at`, and `parse_since` compares with `>=` (inclusive,
     * matching [EventsBackend.fetchChangedSince]'s own contract) - so the last row of each page
     * reappears as the first row of the next. Rows are therefore collected into a map keyed by
     * server id (a repeat overwrites itself rather than duplicating), the loop stops the moment
     * `next` repeats the cursor it was just given (which is what a whole page sharing one
     * `updated_at` would produce), and [MAX_PAGES] caps it outright.
     */
    private suspend fun fetchPagesSince(sinceIso: String?): List<RemoteEvent> {
        val collected = LinkedHashMap<String, DjangoEventRow>()
        var cursor = sinceIso
        var pages = 0
        while (pages < MAX_PAGES) {
            val query = cursor?.let { mapOf("since" to it) } ?: emptyMap()
            val page = decode(DjangoEventPage.serializer(), http.get(EVENTS_PATH, query).getOrThrow().body)
            page.results.forEach { collected[it.id] = it }
            val next = page.next
            if (next == null || next == cursor) break
            cursor = next
            pages++
        }
        return collected.values.map { it.toRemoteEvent() }
    }

    override suspend fun fetchActive(): Result<List<RemoteEvent>> =
        translatingEngineCall("load your dates and notes") {
            // No server-side "active only" filter exists on this endpoint (unlike Postgrest's
            // `deleted_at IS NULL`), so the tombstone filter happens here - the interface's own
            // contract is "every active (not soft-deleted) event", and honouring it in the client
            // is the difference between this transport and the Supabase one, not a change to what
            // callers get.
            fetchPagesSince(null).filterNot { it.deleted }
        }

    override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteEvent>> =
        translatingEngineCall("load changed dates and notes") {
            // Tombstones deliberately NOT filtered - see fetchChangedSince's own doc comment on
            // EventsBackend for why a soft-deleted row must reach EventsSync.pull's tombstone
            // branch.
            fetchPagesSince(Instant.ofEpochMilli(sinceMs).toString())
        }

    override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> =
        translatingEngineCall(if (serverId == null) "save that" else "update that") {
            val body = djangoJson.encodeToString(
                DjangoEventWrite.serializer(),
                DjangoEventWrite.from(fields, originGuid = null),
            )
            val response = if (serverId == null) {
                http.post(EVENTS_PATH, body).getOrThrow()
            } else {
                http.patch("$EVENTS_PATH/$serverId", body).getOrThrow()
            }
            decode(DjangoEventRow.serializer(), response.body).toRemoteEvent()
        }

    /**
     * `DELETE /api/events/<id>`. **`Result.success(false)` means only "no row with that id" (404),
     * never "the row was already tombstoned"** - `EventDetailView.delete` is deliberately
     * idempotent and returns 204 for both a fresh delete and a repeat, so this transport genuinely
     * cannot tell them apart. That is a narrower `false` than
     * [com.kevin.legion.backend.SupabaseEventsBackend.softDelete]'s (which sees the affected-row
     * count), and it is safe in the direction that matters: `true` here always means the row is
     * tombstoned server-side now, which is the only thing any caller acts on.
     */
    override suspend fun softDelete(serverId: String): Result<Boolean> =
        deletingEngineRow("remove that") { http.delete("$EVENTS_PATH/$serverId") }

    override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> =
        Result.failure(
            EngineHttpException(
                EngineFailure.Refused(
                    status = HTTP_NOT_FOUND,
                    body = "The engine has no endpoint for skipping one occurrence yet - " +
                        "nothing was sent and nothing was skipped.",
                ),
            ),
        )

    override suspend fun fetchSkips(serverId: String): Result<List<Long>> =
        Result.failure(
            EngineHttpException(
                EngineFailure.Refused(
                    status = HTTP_NOT_FOUND,
                    body = "The engine has no endpoint for skipped occurrences yet - " +
                        "this is unknown, not empty.",
                ),
            ),
        )

    /**
     * `POST /api/events` carrying `origin_guid`, which `EventListCreateView.post` treats as an
     * idempotency key: an existing row with that guid comes back as a **200** with the row already
     * stored, a genuinely new one as a **201**. That maps onto this function's own
     * `Result<Boolean>` contract exactly - `false` for "already present, nothing written", `true`
     * for "created" - without this file needing a pre-flight SELECT the way
     * [com.kevin.legion.backend.SupabaseEventsBackend.uploadMigratedEvent] does.
     *
     * **Skip dates refuse the whole call, before anything is written.** There is no
     * `event_skips` endpoint (see this class's own doc comment), so an upload carrying them could
     * only ever land the event row and silently drop its skips - a partial write, which
     * CLAUDE.md section 4 rule 2's posture forbids even here where no gate is involved. The check
     * happens first so the refusal costs one comparison, not a round trip.
     */
    override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> {
        if (event.skipDatesEpochMs.isNotEmpty()) {
            return Result.failure(
                EngineHttpException(
                    EngineFailure.Refused(
                        status = HTTP_NOT_FOUND,
                        body = "The engine has no endpoint for skipped occurrences yet, so this " +
                            "event's ${event.skipDatesEpochMs.size} skipped date(s) could not go " +
                            "with it - nothing was sent.",
                    ),
                ),
            )
        }
        return translatingEngineCall("upload a date or note") {
            val body = djangoJson.encodeToString(
                DjangoEventWrite.serializer(),
                DjangoEventWrite.from(event.fields, originGuid = event.originGuid),
            )
            val response = http.post(EVENTS_PATH, body).getOrThrow()
            // Decoded even though only the status is read, so a 2xx carrying a body this client
            // cannot understand fails HERE (as Malformed) rather than being reported as a
            // successful upload of something nobody checked.
            decode(DjangoEventRow.serializer(), response.body)
            // 201 = this call created the row; 200 = EventListCreateView.post matched the
            // origin_guid and handed back the row that was already there. The two bodies are
            // indistinguishable, which is exactly why EngineOk keeps the status - see its own
            // doc comment.
            response.status == HTTP_CREATED
        }
    }

    private companion object {
        /** 200 pages x 500 rows = 100k events, two orders of magnitude past the 437 the live
         * engine holds today - a ceiling that exists so a server-side pagination bug can never
         * hang a foreground pull, not because the number itself is meaningful. */
        const val MAX_PAGES = 200
    }
}
