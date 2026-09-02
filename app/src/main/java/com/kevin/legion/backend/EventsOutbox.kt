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
 * A local write-then-push funnel for a brand-new `kind = event` row, the write-through
 * [com.kevin.legion.notes.NotesController.applyChange]'s own class doc names as the model to copy
 * the SHAPE of - resolve a backend, write local, act on the backend's result - but not its
 * failure posture: reminders drop a failed configured write entirely (CLAUDE.md's outcome-verb
 * rule applied by returning null and writing nothing); an event's local write always happens
 * first, unconditionally, so the row is on-screen immediately, and a push failure enqueues an
 * [OutboxEntry] instead of losing the mutation - see this file's own class doc for why this
 * departure from the reminder model is this ticket's whole point (a phone that is offline when an
 * appointment gets voice-created must not need the driver to remember to retry later).
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
}

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
     */
    suspend fun drain(context: Context, backend: EventsBackend): DrainReport {
        val db = CarDatabase.getDatabase(context)
        val dao = db.outboxDao()
        val pending = dao.pendingForTable(OutboxTarget.EVENTS, MAX_ATTEMPTS)

        var succeeded = 0
        var stillPending = 0
        var poisoned = 0

        for (entry in pending) {
            if (entry.operation != OutboxOperation.UPSERT) continue
            val payload = Json.decodeFromString(EventUpsertOutboxPayload.serializer(), entry.payload)
            val result = backend.uploadMigratedEvent(payload.toMigratedEvent())
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
     */
    suspend fun maybeDrain(context: Context) {
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        if (SupabaseAuth(app).currentUserId() == null) return
        try {
            val report = drain(app, SupabaseEventsBackend(client))
            MidnightEvents.eventsOutboxDrainSucceeded(report.succeeded, report.stillPending, report.poisoned)
        } catch (e: Exception) {
            MidnightEvents.eventsOutboxDrainFailed(e)
        }
    }
}
