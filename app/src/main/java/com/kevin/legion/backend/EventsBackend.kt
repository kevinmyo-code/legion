package com.kevin.legion.backend

/**
 * A `public.events` row as Postgres reports it
 * (`supabase/migrations/20260825000400_aspect_dates_notes_merged.sql`) - the shape
 * [SupabaseEventsBackend] hands back after every write, and the shape [EventsReconcile] copies
 * into the Room replica ([com.kevin.legion.data.local.EventReplicaDao]). Field-for-field mirror of
 * that migration's own header comment mapping table - see [EventReplica][com.kevin.legion.data.local.EventReplica]
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
 * [com.kevin.legion.data.local.EventReplica]'s own doc comment for the NULLS LAST ordering policy
 * this column now requires everywhere it is sorted on.
 */
data class RemoteEvent(
    val serverId: String,
    val title: String,
    val startsAtMs: Long?,
    val endsAtMs: Long?,
    val allDay: Boolean,
    val location: String?,
    val notes: String?,
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
    /** Phase 4 migration provenance (`supabase/migrations/20260826000100_origin_guid.sql`) - null
     * for anything created after cutover through [EventsBackend.upsert], set only on a row
     * [EventsBackend.uploadMigratedEvent] wrote. [EventsReconcile]'s diff reads it to tell
     * "not yet migrated" apart from "created directly against the server", same role as
     * [RemoteReceipt.originGuid]. */
    val originGuid: String? = null,
)

/**
 * Every writable column on `public.events` EXCEPT the ones an upsert never sets directly ([id]/
 * `origin_guid`/`created_at`/`updated_at`/`deleted_at`/`provenance` - the last five are server- or
 * ack-side facts, not caller intent). Shared by [EventsBackend.upsert]'s create and update
 * branches, and by [EventsReconcile]'s merge of the two engine record shapes into one - see that
 * object's own doc comment for exactly how a Dates `Event` and a Notes `Item` each become one of
 * these.
 */
data class EventFields(
    val title: String,
    val startsAtMs: Long?,
    val endsAtMs: Long? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val notes: String? = null,
    val source: String = "legion",
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
