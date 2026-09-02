package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.OutboxEntry
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.data.local.nextAppointmentId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The push half of `kind = event` sync - write-through on create, plus the durable outbox that
 * makes an offline write survive rather than being silently dropped. Read this file's three
 * pieces top to bottom: [EventUpsertOutboxPayload] (the wire shape queued), [EventsAppointmentWriter]
 * (the ONE writer of a new `kind = event` row as of this ticket - `service/LiveToolbox.kt`'s
 * `addAppointment` calls this rather than touching [com.kevin.legion.data.local.EventDao] itself),
 * and [EventsOutboxDrain] (what runs on app foreground to retry anything the writer couldn't send).
 *
 * **Why `uploadMigratedEvent`, not `upsert`, is what a create pushes through - traced, not
 * assumed.** [EventsBackend.upsert] with `serverId = null` unconditionally INSERTs
 * ([SupabaseEventsBackend]'s own `upsert` branch on a null id) - there is no natural key for it to
 * upsert ON (see [EventsBackend.upsert]'s own doc comment: "there is no natural key to upsert ON").
 * Calling it twice for the same logical event - the exact shape a drain retry after an ack that
 * was itself lost in transit produces - creates TWO server rows, not one. [EventsBackend.uploadMigratedEvent]
 * is the one function on this interface that IS idempotent by an identity the caller controls: it
 * checks `origin_guid` first and returns `Result.success(false)` (never a duplicate insert) when a
 * row already carries the [MigratedEvent.originGuid] this call would have used
 * ([SupabaseEventsBackend]'s own implementation, traced). Reusing it for a live, voice-created
 * event (not a migration) is a deliberate widening of an existing mechanism to a new caller with
 * the identical need, not a new gate of its own - [EventsBackend]'s own class doc already frames
 * [uploadMigratedEvent] as existing "because the migration needs to attach originGuid for
 * idempotency", which is exactly what a retryable outbox entry needs too.
 *
 * **What this means for [Event.serverId]: it is NOT set by a successful push.** [uploadMigratedEvent]
 * returns `Result<Boolean>`, never the created row, so there is no server uuid available to write
 * back locally at the moment a push succeeds. This is accepted, not an oversight - the row's local
 * [Event.guid] already equals the value the server now files under `origin_guid`, so the very next
 * [EventsSync.pull] (already scheduled right after a drain - see [EventsOutboxDrain]'s own doc
 * comment for why the ordering is load-bearing) matches this local row by guid and fills in the
 * real [Event.serverId] through the ordinary merge path, exactly as [Event.serverId]'s own v59 doc
 * comment describes.
 */
@Serializable
private data class EventUpsertOutboxPayload(
    val guid: String,
    val title: String,
    val startsAtMs: Long?,
    val endsAtMs: Long?,
    val allDay: Boolean,
    val source: String,
    val kind: String,
    val createdAtMs: Long?,
) {
    fun toMigratedEvent() = MigratedEvent(
        originGuid = guid,
        fields = EventFields(
            title = title,
            startsAtMs = startsAtMs,
            createdAtMs = createdAtMs,
            endsAtMs = endsAtMs,
            allDay = allDay,
            source = source,
            kind = kind,
        ),
    )

    companion object {
        fun from(row: Event) = EventUpsertOutboxPayload(
            guid = row.guid,
            title = row.title,
            startsAtMs = row.startsAt,
            endsAtMs = row.endsAt,
            allDay = row.allDay,
            source = row.source,
            kind = row.kind,
            createdAtMs = row.createdAt,
        )
    }
}

/**
 * The wire shape queued for [OutboxOperation.UPDATE] - a rename/reschedule of an event that has
 * ALREADY round-tripped (a real, non-placeholder [Event.serverId] known). **Whole-row, not just
 * the fields a rename touches** - [SupabaseEventsBackend.upsert]'s own `EventUpsertDto` sends
 * every writable column on every write (that DTO's own doc comment: "reproduces whole-row-replace
 * semantics"), so a payload carrying only title/start/end would silently blank out `done`/
 * `repeatKind`/every other column the row already had. [from] snapshots the row's own current
 * values for everything else, exactly as [com.kevin.legion.notes.NotesController]'s own
 * `ListItem.toEventFields()` does for a reminder's identical whole-row echo.
 */
@Serializable
private data class EventUpdateOutboxPayload(
    val serverId: String,
    val title: String,
    val startsAtMs: Long?,
    val endsAtMs: Long?,
    val allDay: Boolean,
    val location: String?,
    val notes: String?,
    val structuredMeta: String?,
    val source: String,
    val kind: String,
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
    val createdAtMs: Long?,
) {
    fun toFields() = EventFields(
        title = title,
        startsAtMs = startsAtMs,
        createdAtMs = createdAtMs,
        endsAtMs = endsAtMs,
        allDay = allDay,
        location = location,
        notes = notes,
        structuredMeta = structuredMeta,
        source = source,
        kind = kind,
        googleEventId = googleEventId,
        done = done,
        doneAtMs = doneAtMs,
        sortOrder = sortOrder,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDateMs = repeatEndDateMs,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAtMs = missedAtMs,
        missedDismissedAtMs = missedDismissedAtMs,
        loggedAtMs = loggedAtMs,
    )

    companion object {
        fun from(row: Event): EventUpdateOutboxPayload {
            val serverId = requireNotNull(row.serverId) {
                "EventUpdateOutboxPayload.from requires an already-round-tripped row"
            }
            return EventUpdateOutboxPayload(
                serverId = serverId,
                title = row.title,
                startsAtMs = row.startsAt,
                endsAtMs = row.endsAt,
                allDay = row.allDay,
                location = row.location,
                notes = row.notes,
                structuredMeta = row.structuredMeta,
                source = row.source,
                kind = row.kind,
                googleEventId = row.googleEventId,
                done = row.done,
                doneAtMs = row.doneAt,
                sortOrder = row.sortOrder,
                triggerPlaceLabel = row.triggerPlaceLabel,
                repeatKind = row.repeatKind,
                repeatEvery = row.repeatEvery,
                repeatDaysOfWeek = row.repeatDaysOfWeek,
                repeatDay = row.repeatDay,
                repeatMonth = row.repeatMonth,
                repeatEndKind = row.repeatEndKind,
                repeatEndDateMs = row.repeatEndDate,
                repeatEndCount = row.repeatEndCount,
                exact = row.exact,
                exactDowngraded = row.exactDowngraded,
                missedAtMs = row.missedAt,
                missedDismissedAtMs = row.missedDismissedAt,
                loggedAtMs = row.loggedAt,
                createdAtMs = row.createdAt,
            )
        }
    }
}

/** The wire shape queued for [OutboxOperation.SOFT_DELETE] - just the server row to tombstone. */
@Serializable
private data class EventDeleteOutboxPayload(val serverId: String)

/**
 * A local write-then-push funnel for a brand-new `kind = event` row, the write-through
 * [com.kevin.legion.notes.NotesController.applyChange]'s own class doc names as the model to copy
 * the SHAPE of - resolve a backend, write local, act on the backend's result - but not its
 * failure posture: reminders drop a failed configured write entirely (CLAUDE.md's outcome-verb
 * rule applied by returning null and writing nothing); an event's local write always happens
 * first, unconditionally, so the row is on-screen immediately, and a push failure enqueues an
 * [OutboxEntry] instead of losing the mutation - see this file's own class doc for why this
 * departure from the reminder model is this ticket's whole point (a phone that is offline when an
 * appointment gets voice-created must not need the driver to remember to retry later).
 *
 * **WIDENED 2026-09-02 (live-sync ticket 04 follow-up, Kevin) to [updateEvent]/[deleteEvent] -
 * renaming or deleting an appointment used to be local-only on every install, a standing ruling
 * from one-today ticket 02 point 3 made when nothing synced at all.** It became a real hole the
 * moment [addEvent] started syncing creation: the two devices would silently diverge on exactly
 * the edit a user is most likely to make. Both new functions copy [addEvent]'s own shape - write
 * local first, unconditionally, so the screen is honest immediately; push if configured; enqueue
 * on failure rather than dropping - **not** the server-first/local-write-on-ack ordering
 * [com.kevin.legion.notes.NotesController.applyChange] uses for a reminder, because that ordering
 * exists there specifically so a REJECTED write is never applied locally at all (CLAUDE.md's
 * outcome-verb rule, applied by dropping); an appointment's failure posture is already the
 * opposite of a reminder's by [addEvent]'s own precedent (enqueue, never drop), so matching THAT
 * precedent rather than reminder's ordering is the one that keeps this file internally consistent.
 * [deleteEvent] soft-deletes, never hard-deletes, on a configured install - the whole reason the
 * old local-only ruling was safe (nothing to reconcile) is exactly what stopped being true once a
 * pull that propagates tombstones exists (ticket 03): a hard local delete would simply resurrect
 * the row on the next pull.
 */
object EventsAppointmentWriter {
    /** Test seam, same mechanism and same purpose as
     * [com.kevin.legion.notes.NotesController.backendOverride] - settable from a unit test so a
     * fake [EventsBackend] can be injected with no real network. Production code never sets this. */
    @Volatile
    internal var backendOverride: EventsBackend? = null

    private fun backend(context: Context): EventsBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabaseEventsBackend(client)
    }

    /**
     * Inserts a new `kind = event` row (an "appointment", in the voice layer's own vocabulary) and
     * returns it. Always writes locally, first, so the caller (`LiveToolbox.addAppointment`) can
     * report success the instant Room accepts the row - a Room failure is the only thing that
     * still throws, matching [com.kevin.legion.data.local.EventDao.insert]'s existing contract at
     * that call site (unchanged by this ticket).
     *
     * On a configured install, also attempts to push the row to the server via
     * [EventsBackend.uploadMigratedEvent] - see this file's own class doc for why that function,
     * not [EventsBackend.upsert]. A push failure never undoes or blocks the local write; it
     * enqueues an [OutboxEntry] so [EventsOutboxDrain] can retry it later. On an install with no
     * Supabase project configured at all, nothing is pushed and nothing is queued - there is no
     * server this device will ever talk to, matching [com.kevin.legion.notes.NotesController]'s
     * identical unconfigured posture.
     */
    suspend fun addEvent(
        context: Context,
        title: String,
        startsAtMs: Long?,
        endsAtMs: Long?,
        allDay: Boolean,
        source: String,
        kind: String,
    ): Event {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val row = Event(
            id = db.eventDao().nextAppointmentId(),
            // Null, never a client-minted placeholder - see [Event.serverId]'s own v59 doc comment
            // for why this is the one thing this ticket changes about how a new row is minted.
            serverId = null,
            guid = java.util.UUID.randomUUID().toString(),
            title = title,
            startsAt = startsAtMs,
            endsAt = endsAtMs,
            allDay = allDay,
            source = source,
            kind = kind,
            updatedAtMs = now,
            createdAt = now,
        )
        db.eventDao().insert(row)

        val backend = backend(context) ?: return row
        val result = backend.uploadMigratedEvent(EventUpsertOutboxPayload.from(row).toMigratedEvent())
        if (result.isFailure) {
            enqueue(db, row, result.exceptionOrNull()?.message)
        }
        return row
    }

    private suspend fun enqueue(db: CarDatabase, row: Event, error: String?) {
        db.outboxDao().insert(
            OutboxEntry(
                targetTable = OutboxTarget.EVENTS,
                operation = OutboxOperation.UPSERT,
                localId = row.id,
                payload = Json.encodeToString(EventUpsertOutboxPayload.serializer(), EventUpsertOutboxPayload.from(row)),
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                lastError = error,
            ),
        )
    }

    /**
     * Renames/reschedules a calendar-table row (`kind = event` or `kind = task` -
     * [com.kevin.legion.notes.NotesController]'s own `isCalendarTableKind`) already read by the
     * caller. Local write always happens - see this object's own class doc for why that ordering,
     * not server-first. On a configured install with a real [Event.serverId] (a row that has
     * genuinely round-tripped), pushes via [EventsBackend.upsert] and enqueues an
     * [OutboxOperation.UPDATE] entry on failure. **A [Event.serverId] of null means the row's own
     * [addEvent] create is still pending in the outbox** (v59 minted null, never a placeholder, for
     * exactly this state - see [Event.serverId]'s own doc comment) - there is nothing server-side
     * yet to target an update AT, so instead the still-queued CREATE entry itself is re-pointed at
     * this row's latest values ([repointPendingCreate]), so the eventual create carries the final
     * title rather than the one typed a moment before the rename.
     */
    suspend fun updateEvent(
        context: Context,
        existing: Event,
        title: String,
        startsAtMs: Long?,
        endsAtMs: Long?,
        allDay: Boolean,
    ): Boolean {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            title = title,
            startsAt = startsAtMs,
            endsAt = endsAtMs,
            allDay = allDay,
            updatedAtMs = now,
        )
        db.eventDao().update(updated)

        val backend = backend(context) ?: return true

        val serverId = existing.serverId
        if (serverId == null) {
            repointPendingCreate(db, updated)
            return true
        }

        val result = backend.upsert(serverId, updated.toEventFields())
        if (result.isFailure) {
            enqueueUpdate(db, updated, result.exceptionOrNull()?.message)
        }
        return true
    }

    /**
     * Soft-deletes a calendar-table row already read by the caller. Local write always happens -
     * marks [Event.deleted] rather than a hard delete, so a resurrecting pull (this row's own
     * tombstone reaching the server late) never finds anything locally left to conflict with. On a
     * configured install with a real [Event.serverId], pushes via [EventsBackend.softDelete] and
     * enqueues an [OutboxOperation.SOFT_DELETE] entry on failure.
     *
     * **A [Event.serverId] of null is the one case this hard-deletes, not soft-deletes** - the
     * row's own [addEvent] create never reached the server (see [updateEvent]'s own doc comment for
     * why null means exactly that), so there is nothing server-side to tombstone; the still-queued
     * create is cancelled outright ([cancelPendingCreate]) and the local row goes with it. **Known,
     * narrow gap, stated rather than hidden:** [EventsOutboxDrain]/[EventsSync.maybeAutoPull] run
     * fire-and-forget on foreground, so there is a real (if short) window where a create has
     * already drained successfully but the following pull has not yet run to fill in a real
     * [Event.serverId] - a delete landing in exactly that window hard-deletes locally with no
     * tombstone sent, and the row could in principle survive server-side. Not solved here; flagged
     * for the same follow-up this ticket's own map entry names for the other five aspects.
     */
    suspend fun deleteEvent(context: Context, existing: Event): Boolean {
        val db = CarDatabase.getDatabase(context)
        val backend = backend(context)
        if (backend == null) {
            db.eventDao().deleteById(existing.id)
            return true
        }

        val serverId = existing.serverId
        if (serverId == null) {
            cancelPendingCreate(db, existing.id)
            db.eventDao().deleteById(existing.id)
            return true
        }

        val now = System.currentTimeMillis()
        db.eventDao().update(existing.copy(deleted = true, updatedAtMs = now))
        val result = backend.softDelete(serverId)
        if (result.isFailure) {
            enqueueDelete(db, existing.id, serverId, result.exceptionOrNull()?.message)
        }
        return true
    }

    private suspend fun enqueueUpdate(db: CarDatabase, row: Event, error: String?) {
        db.outboxDao().insert(
            OutboxEntry(
                targetTable = OutboxTarget.EVENTS,
                operation = OutboxOperation.UPDATE,
                localId = row.id,
                payload = Json.encodeToString(EventUpdateOutboxPayload.serializer(), EventUpdateOutboxPayload.from(row)),
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                lastError = error,
            ),
        )
    }

    private suspend fun enqueueDelete(db: CarDatabase, localId: Long, serverId: String, error: String?) {
        db.outboxDao().insert(
            OutboxEntry(
                targetTable = OutboxTarget.EVENTS,
                operation = OutboxOperation.SOFT_DELETE,
                localId = localId,
                payload = Json.encodeToString(EventDeleteOutboxPayload.serializer(), EventDeleteOutboxPayload(serverId)),
                createdAt = System.currentTimeMillis(),
                attempts = 0,
                lastError = error,
            ),
        )
    }

    /** Re-points an already-queued [OutboxOperation.UPSERT] create entry for [row] at its latest
     * field values, by delete-then-reinsert rather than an in-place payload update - no DAO method
     * exists to patch a queued payload in place, and adding one would touch the outbox's own
     * generic `sync_outbox` schema for a single caller's convenience. A fresh `createdAt`/reset
     * `attempts` is an accepted side effect: the entry is functionally the same pending create, now
     * carrying the values the row actually has. A no-op (never called with nothing pending) is
     * indistinguishable from this function simply doing nothing - there is no pending create left
     * to re-point once [addEvent]'s own push already succeeded, which is the ordinary case. */
    private suspend fun repointPendingCreate(db: CarDatabase, row: Event) {
        val dao = db.outboxDao()
        val pending = dao.pendingForTable(OutboxTarget.EVENTS, Int.MAX_VALUE)
            .filter { it.operation == OutboxOperation.UPSERT && it.localId == row.id }
        for (entry in pending) {
            dao.delete(entry.id)
        }
        if (pending.isNotEmpty()) enqueue(db, row, pending.last().lastError)
    }

    /** Cancels every still-queued [OutboxOperation.UPSERT] create entry for [localId] - see
     * [deleteEvent]'s own doc comment for why a null [Event.serverId] means there is nothing
     * server-side yet for this cancellation to need to reach. */
    private suspend fun cancelPendingCreate(db: CarDatabase, localId: Long) {
        val dao = db.outboxDao()
        val pending = dao.pendingForTable(OutboxTarget.EVENTS, Int.MAX_VALUE)
            .filter { it.operation == OutboxOperation.UPSERT && it.localId == localId }
        for (entry in pending) {
            dao.delete(entry.id)
        }
    }
}

/** [Event] -> [EventFields], field for field - the whole-row-replace shape
 * [EventsBackend.upsert] and [EventsAppointmentWriter.updateEvent] need for a rename/reschedule
 * push, matching [com.kevin.legion.notes.NotesController]'s own `ListItem.toEventFields()` for the
 * identical reason (see that function's own doc comment). Private to this file's own writer;
 * duplicated rather than exported because [com.kevin.legion.backend.EventsReconcile]'s private
 * `EventFields(...)` helper and [com.kevin.legion.notes.NotesController]'s `toEventFields()` each
 * already have a shape suited to their own caller, and a shared version would need to become the
 * least-common-denominator of three different mapping needs for no real benefit. */
private fun Event.toEventFields(): EventFields = EventFields(
    title = title,
    startsAtMs = startsAt,
    createdAtMs = createdAt,
    endsAtMs = endsAt,
    allDay = allDay,
    location = location,
    notes = notes,
    structuredMeta = structuredMeta,
    source = source,
    kind = kind,
    googleEventId = googleEventId,
    done = done,
    doneAtMs = doneAt,
    sortOrder = sortOrder,
    triggerPlaceLabel = triggerPlaceLabel,
    repeatKind = repeatKind,
    repeatEvery = repeatEvery,
    repeatDaysOfWeek = repeatDaysOfWeek,
    repeatDay = repeatDay,
    repeatMonth = repeatMonth,
    repeatEndKind = repeatEndKind,
    repeatEndDateMs = repeatEndDate,
    repeatEndCount = repeatEndCount,
    exact = exact,
    exactDowngraded = exactDowngraded,
    missedAtMs = missedAt,
    missedDismissedAtMs = missedDismissedAt,
    loggedAtMs = loggedAt,
)

/**
 * Retries every still-pending `events` outbox entry - `ui/MainActivity.kt`'s `onResume` hook,
 * alongside [EventsSync.maybeAutoPull]. **Called BEFORE that pull, deliberately - do not reverse
 * this ordering.** [EventsSync.pull] resolves a same-timestamp tie toward the SERVER (that
 * function's own "rule 4" comment: "this device has no push side yet, so the server is the only
 * copy every other device will ever converge on"). That reasoning breaks the moment a push side
 * exists: if a drain ran AFTER a pull, a local row still sitting in the outbox would look to that
 * pull exactly like "a local row the server does not have" (correctly left alone, by pull's own
 * rule 6) - fine on its own - but a row this SAME drain is about to successfully push would then
 * need a SECOND pull to ever become visible as synced, and in between, a concurrent edit from the
 * other phone landing on the server would have nothing local to reconcile against yet. Draining
 * first means every local mutation this device knows about is at least ATTEMPTED against the
 * server before that server's own state is read back - the same "read after your own write, not
 * before" ordering [MainActivity]'s own call site comment repeats for exactly this reason.
 */
object EventsOutboxDrain {
    /** A poisoned entry (see [OutboxEntry]'s own doc comment) stops being retried once it has
     * failed this many times - CLAUDE.md's own "a poison row that retries every foreground forever
     * is worse than one that stops and says so", applied as a bound on ATTEMPT COUNT rather than
     * on failure TYPE. **This is a deliberate, traced substitution for a 4xx-vs-5xx classification,
     * not an oversight**: [SupabaseEventsBackend]'s own `translating` helper collapses every
     * `io.github.jan.supabase.exceptions.RestException` (which covers BOTH a genuine client-side
     * rejection and a 5xx from Postgrest) into one [EventsBackendException] carrying only a
     * message string - there is no HTTP status code left by the time a caller of [EventsBackend]
     * ever sees the failure, so "was this rejected or just unreachable" cannot be answered at this
     * layer without first widening that translation (out of scope for this ticket). A bounded
     * attempt count gives the exact guarantee CLAUDE.md's own language asks for - "must NOT be
     * retried forever" - using the granularity this interface actually exposes today, at the cost
     * of retrying a genuinely-rejected write a few times before it poisons rather than recognizing
     * the rejection on attempt one. */
    const val MAX_ATTEMPTS = 5

    data class DrainReport(val succeeded: Int, val stillPending: Int, val poisoned: Int)

    /**
     * Retries every `events` [OutboxEntry] still under [MAX_ATTEMPTS]. **Idempotent by
     * construction, not merely by intent** - every entry's payload carries the row's own
     * [Event.guid] as [MigratedEvent.originGuid], and [EventsBackend.uploadMigratedEvent] is
     * itself idempotent on that key (this file's own class doc traces exactly how), so draining
     * the same still-pending entry twice in a row - the ordinary shape of "app foregrounded twice
     * before the first drain's own success response came back" - can create at most one server
     * row for it, never two.
     *
     * **WIDENED 2026-09-02 (live-sync ticket 04 follow-up) to also drain [OutboxOperation.UPDATE]/
     * [OutboxOperation.SOFT_DELETE].** Before this, the dispatch below silently `continue`d past
     * either kind of entry forever - harmless only because nothing had ever produced one yet (see
     * [OutboxOperation]'s own class doc for that history). The bounded-attempts/poison mechanics
     * this doc comment describes above are UNCHANGED; only which payload shape gets decoded and
     * which [EventsBackend] call gets made varies by [OutboxEntry.operation] now.
     */
    suspend fun drain(context: Context, backend: EventsBackend): DrainReport {
        val db = CarDatabase.getDatabase(context)
        val dao = db.outboxDao()
        val pending = dao.pendingForTable(OutboxTarget.EVENTS, MAX_ATTEMPTS)

        var succeeded = 0
        var stillPending = 0
        var poisoned = 0

        for (entry in pending) {
            val result: Result<*> = when (entry.operation) {
                OutboxOperation.UPSERT -> {
                    val payload = Json.decodeFromString(EventUpsertOutboxPayload.serializer(), entry.payload)
                    backend.uploadMigratedEvent(payload.toMigratedEvent())
                }
                OutboxOperation.UPDATE -> {
                    val payload = Json.decodeFromString(EventUpdateOutboxPayload.serializer(), entry.payload)
                    backend.upsert(payload.serverId, payload.toFields())
                }
                OutboxOperation.SOFT_DELETE -> {
                    val payload = Json.decodeFromString(EventDeleteOutboxPayload.serializer(), entry.payload)
                    backend.softDelete(payload.serverId)
                }
                else -> continue
            }
            if (result.isSuccess) {
                dao.delete(entry.id)
                succeeded++
                continue
            }
            val attempts = entry.attempts + 1
            val message = result.exceptionOrNull()?.message ?: "unknown error"
            dao.recordAttempt(entry.id, attempts, message)
            if (attempts >= MAX_ATTEMPTS) poisoned++ else stillPending++
        }

        return DrainReport(succeeded = succeeded, stillPending = stillPending, poisoned = poisoned)
    }

    /**
     * `MainActivity.onResume`'s hook - no-ops silently (same posture as
     * [EventsSync.maybeAutoPull]) when Supabase is not configured or nobody is signed in, both
     * ordinary states this must never crash or dialog on. Fire-and-forget is deliberately NOT used
     * here, unlike [EventsSync.maybeAutoPull]'s own scope - the caller (`MainActivity.onResume`)
     * awaits this before calling [EventsSync.maybeAutoPull], which is the entire point (see this
     * object's own class doc for why the ordering matters); a fire-and-forget drain racing its own
     * pull would reintroduce the exact hazard this function exists to close.
     *
     * **Cold-start fix, 2026-09-02.** The guard used to be a raw `currentUserId() == null` read -
     * the same race [EventsSync.maybeAutoPull]'s own doc comment traces, never carried over here.
     * This function is already `suspend`, so it awaits [SupabaseAuth.resolveSignedInUserId] instead.
     */
    suspend fun maybeDrain(context: Context) {
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        if (SupabaseAuth(app).resolveSignedInUserId() == null) return
        try {
            val report = drain(app, SupabaseEventsBackend(client))
            MidnightEvents.eventsOutboxDrainSucceeded(report.succeeded, report.stillPending, report.poisoned)
        } catch (e: Exception) {
            MidnightEvents.eventsOutboxDrainFailed(e)
        }
    }
}
