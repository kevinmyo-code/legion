package com.kevin.legion.backend

/**
 * The two values `public.events.kind` may hold (`supabase/migrations/20260827000200_events_kind.sql`) -
 * what tells [com.kevin.legion.notes.NotesController] and [EventsReconcile] which rows they own. A
 * Notes `Item` is always [REMINDER]; a Dates `Event` (legion-authored or a Google import) is always
 * [APPOINTMENT]. Set once at upload, from the record type read - never derived from a row's shape
 * (`sortOrder`, `source`, anything else that happens to correlate today). See the migration's own
 * comment for why the server default is [REMINDER], not a guess: an origin the app cannot identify
 * is treated as something it owns, the direction that fails visibly rather than silently.
 *
 * A third kind, `car_task`, briefly existed here for a `car_tasks` fold into `events`
 * (backend-erp ticket 06's original ruling, ticket 10's wave) and was reverted the same day: the
 * fold turned out to be a duplicate of one Notes had already performed years earlier (`car_tasks`
 * rows were copied into `list_items` by `MIGRATION_9_10`, and that guid already reaches `events`
 * as `kind = reminder` today). See `.scratch/backend-erp/issues/06-fleet-has-no-server-home.md`'s
 * reversal entry and `supabase/migrations/20260828000300_events_kind_car_task_reverted.sql`.
 */
object EventKind {
    const val REMINDER = "reminder"
    const val APPOINTMENT = "appointment"
}

/**
 * A `public.events` row as Postgres reports it
 * (`supabase/migrations/20260825000400_aspect_dates_notes_merged.sql`) - the shape
 * [SupabaseEventsBackend] hands back after every write, and the shape [EventsReconcile] copies
 * into the Room table ([com.kevin.legion.data.local.EventDao]). Field-for-field mirror of
 * that migration's own header comment mapping table - see [Event][com.kevin.legion.data.local.Event]
 * for the Room side of the same shape.
 *
 * [updatedAtMs] is the server's own `updated_at` - the "as of" clock for the cache-first read path
 * (ticket 01 ruling 9), same role as [RemotePlace.updatedAtMs]. Unlike [RemoteReceipt], events are
 * AUTHORED not gated, so this DOES get bumped on every edit.
 *
 * [startsAtMs] is nullable (backend-erp ticket 07, "RULED 2026-08-26: option 1") - `starts_at` was
 * widened to nullable server-side (`supabase/migrations/20260826000400_events_starts_at_nullable.sql`)
 * because a genuinely dateless Notes `Item` (measured 53 of 56 real rows) has no date to state. A
 * null here is never a guessed one; see [EventsReconcile]'s class doc for the merge ruling and
 * [com.kevin.legion.data.local.Event]'s own doc comment for the NULLS LAST ordering policy
 * this column now requires everywhere it is sorted on.
 */
data class RemoteEvent(
    val serverId: String,
    val title: String,
    /** `public.events.created_at`, `timestamptz not null default now()` - the row's real creation
     * instant, never re-derived from when a sync or migration happened to run.
     * [com.kevin.legion.backend.EventsReconcile] carries the ORIGINATING engine record's own
     * [com.kevin.legion.data.local.EngineRecord.createdAt] through [EventFields.createdAtMs] into
     * [MigratedEvent.fields] for exactly this reason - a migrated row's `created_at` must read as
     * the note's real age, not the moment the one-time upload ran. See [EventsReconcile]'s own
     * class doc for the defect this closes. */
    val createdAtMs: Long,
    val startsAtMs: Long?,
    val endsAtMs: Long?,
    val allDay: Boolean,
    val location: String?,
    val notes: String?,
    /** The parsed `LEGION::v1` description-block map, as the server's own `jsonb` column
     * round-trips it: a compact JSON object string (e.g. `{"course":"COSC4320"}`), or null when
     * the event carries no such block. Added alongside `public.events.structured_meta`
     * (`supabase/migrations/20260827000100_events_structured_meta.sql`, UNAPPLIED as of that
     * migration's own doc comment) so the class-schedule metadata
     * [com.kevin.legion.calendar.CalendarImportController] rescues out of a raw Google description
     * string survives past the engine's own eventual retirement (ticket 01 ruling 11 / ruling 7) -
     * a phone-only engine field alone would only defer the loss, not prevent it. Kept as an opaque
     * JSON string here rather than parsed into a `Map` - this class mirrors the wire shape, same
     * posture the rest of [RemoteEvent] already takes for every other column. */
    val structuredMeta: String? = null,
    val source: String,
    val googleEventId: String?,
    val done: Boolean,
    val doneAtMs: Long?,
    val sortOrder: Int?,
    val triggerPlaceLabel: String?,
    val repeatKind: String?,
    val repeatEvery: Int?,
    val repeatDaysOfWeek: String?,
    val repeatDay: Int?,
    val repeatMonth: Int?,
    val repeatEndKind: String?,
    val repeatEndDateMs: Long?,
    val repeatEndCount: Int?,
    val exact: Boolean,
    val exactDowngraded: Boolean,
    val missedAtMs: Long?,
    val missedDismissedAtMs: Long?,
    val loggedAtMs: Long?,
    val updatedAtMs: Long,
    val deleted: Boolean,
    /** [EventKind.REMINDER] or [EventKind.APPOINTMENT] - see that object's own doc comment.
     * Defaults to [EventKind.REMINDER] only as a decode-time placeholder matching the column's own
     * server-side default; every real row states one explicitly. */
    val kind: String = EventKind.REMINDER,
    /** Phase 4 migration provenance (`supabase/migrations/20260826000100_origin_guid.sql`) - null
     * for anything created after cutover through [EventsBackend.upsert], set only on a row
     * [EventsBackend.uploadMigratedEvent] wrote. [EventsReconcile]'s diff reads it to tell
     * "not yet migrated" apart from "created directly against the server", same role as
     * [RemoteReceipt.originGuid]. */
    val originGuid: String? = null,
)

/**
 * Every writable column on `public.events` EXCEPT the ones an upsert never sets directly ([id]/
 * `origin_guid`/`updated_at`/`deleted_at`/`provenance` - these four are server- or ack-side facts,
 * not caller intent). Shared by [EventsBackend.upsert]'s create and update branches, and by
 * [EventsReconcile]'s merge of the two engine record shapes into one - see that object's own doc
 * comment for exactly how a Dates `Event` and a Notes `Item` each become one of these.
 *
 * **`created_at` is the one exception to "caller intent"** - [createdAtMs] is nullable and
 * defaults to null (see that property's own doc comment) precisely because a caller usually has no
 * real intent about it at all; the field exists so [EventsReconcile] and a live edit CAN state a
 * real value when they have one, not because every write must supply one.
 */
data class EventFields(
    val title: String,
    val startsAtMs: Long?,
    /** The row's real creation instant, when the caller has one - null means "unknown, let the
     * server's own `created_at default now()` decide" (an ordinary live [EventsBackend.upsert]
     * create has nothing truer to offer than "now" anyway). [EventsReconcile] always supplies a
     * real, non-null value here (the originating [com.kevin.legion.data.local.EngineRecord]'s own
     * [com.kevin.legion.data.local.EngineRecord.createdAt]) precisely because "unknown" is NOT
     * true for a migrated row - it has a real prior creation time and asserting ignorance of it
     * would be the same kind of invented-by-omission fact CLAUDE.md section 4 rule 5 forbids. See
     * `SupabaseEventsBackend`'s `EventUpsertDto.createdAt` for how a null here is kept OFF the wire
     * on write, rather than sent as a literal JSON null into a `NOT NULL` column. */
    val createdAtMs: Long? = null,
    val endsAtMs: Long? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    /** Same field as [RemoteEvent.structuredMeta] - see that property's own doc comment. Null for
     * every legion-authored event and for a Google event with no `LEGION::v1` block, which is most
     * of them. */
    val structuredMeta: String? = null,
    val source: String = "legion",
    /** [EventKind.REMINDER] or [EventKind.APPOINTMENT] - see that object's own doc comment.
     * Defaults to [EventKind.REMINDER] because every caller except [EventsReconcile]'s Dates
     * branch IS a Notes `Item` (a live [com.kevin.legion.notes.NotesController] write never
     * produces an appointment); the Dates branch overrides it explicitly. */
    val kind: String = EventKind.REMINDER,
    val googleEventId: String? = null,
    val done: Boolean = false,
    val doneAtMs: Long? = null,
    val sortOrder: Int? = null,
    val triggerPlaceLabel: String? = null,
    val repeatKind: String? = null,
    val repeatEvery: Int? = null,
    val repeatDaysOfWeek: String? = null,
    val repeatDay: Int? = null,
    val repeatMonth: Int? = null,
    val repeatEndKind: String? = null,
    val repeatEndDateMs: Long? = null,
    val repeatEndCount: Int? = null,
    val exact: Boolean = false,
    val exactDowngraded: Boolean = false,
    val missedAtMs: Long? = null,
    val missedDismissedAtMs: Long? = null,
    val loggedAtMs: Long? = null,
)

/**
 * One already-gated... no - **events are AUTHORED, not gated** (the migration's own header
 * comment: "freely editable... the immutability trigger does not apply here"), so unlike
 * [MigratedReceipt] this is not "already verified, just transfer it" - it is the same [EventFields]
 * shape plus the phase-4 migration-provenance key. [originGuid] is the engine record's own
 * `records.guid` ([com.kevin.legion.data.local.EngineRecord.guid]) - the same idempotency key
 * every other Phase 4 aspect uses (`supabase/migrations/20260826000100_origin_guid.sql`).
 * [skipDatesEpochMs] carries that engine `Item`'s `list_item_skips` rows along in the SAME upload,
 * since a skip has nowhere else to come from and the target event's server-side [RemoteEvent.serverId]
 * only exists once this upload itself creates it.
 */
data class MigratedEvent(
    val originGuid: String,
    val fields: EventFields,
    val skipDatesEpochMs: List<Long> = emptyList(),
)

/**
 * The Phase 4 events seam, mirroring [PlacesBackend]/[PantryBackend]'s shape (narrow, no
 * [io.github.jan.supabase.SupabaseClient] in any signature, every function returns [Result]).
 * `notes/NotesController.kt` and `engine/dates/DatesAgenda.kt` are the production callers.
 *
 * **Unlike [PantryBackend], there is only ONE write path here, not two kept deliberately apart.**
 * Events are authored, not gated (no server-side reconciliation to bypass), so a live write and a
 * migration-time write both land through ordinary upsert semantics - [upsert] for the former,
 * [uploadMigratedEvent] for the latter exists only because the migration needs to attach
 * [MigratedEvent.originGuid] for idempotency and to carry `event_skips` along, not because it
 * skips a gate the live path runs.
 */
interface EventsBackend {
    /** Every active (not soft-deleted) event, server-side. Used to refresh the Room replica -
     * never called from a hot-path read; callers read the replica instead. */
    suspend fun fetchActive(): Result<List<RemoteEvent>>

    /**
     * Creates a new event ([serverId] null) or updates an existing one ([serverId] the row's own
     * uuid). Unlike [PlacesBackend.upsert], there is no natural key to upsert ON - `public.events`
     * has no unique column besides the server-generated [RemoteEvent.serverId] itself (and a
     * partial one on `google_event_id`, irrelevant to a legion-authored row) - so "create vs
     * update" is a genuine fork on whether the caller already has an id, not a single SQL
     * `ON CONFLICT`.
     */
    suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent>

    /** Soft-deletes the event at [serverId]. `Result.success(false)` means no active row matched
     * (already deleted, or a stale id) - a normal, expected outcome, never reported as a delete
     * having happened. */
    suspend fun softDelete(serverId: String): Result<Boolean>

    /** Records one skipped occurrence in `public.event_skips`. Idempotent by the table's own
     * `(event_id, skip_date)` unique constraint - re-skipping the same date is a no-op, reported
     * as `Result.success(Unit)` either way since a skip that already existed is still, from the
     * caller's point of view, skipped. */
    suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit>

    /** Every skipped date for [serverId], ascending. Used to refresh the skips half of the Room
     * replica. */
    suspend fun fetchSkips(serverId: String): Result<List<Long>>

    /**
     * The one-time migration upload for an engine record ([MigratedEvent]) not yet mirrored
     * server-side. `Result.success(false)` means a row with this [MigratedEvent.originGuid] was
     * already present (a re-run, per ticket 05 phase 4 step 1: "a re-run is free") - the skips are
     * NOT re-attempted in that case either, since the event row (and therefore its server id) did
     * not change. `Result.failure` means the request itself did not complete.
     */
    suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean>
}

/** Thrown (wrapped in [Result.failure]) by [SupabaseEventsBackend] for every failure branch -
 * owned by this package, never a raw supabase-kt/Ktor exception, same posture as
 * [PlacesBackendException]/[PantryBackendException]. */
class EventsBackendException(message: String) : Exception(message)
