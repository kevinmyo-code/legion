package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The first slice of live sync for `public.events` - a real, automatic PULL that MERGES server
 * state into the phone's local `events` table, replacing nothing.
 *
 * **Why this exists, traced 2026-09-02.** Nothing in the app ever pulled server changes
 * automatically before this. The only existing route, [EventsReconcile] (a one-shot Settings
 * button), uploads local rows and then WIPES the local `kind = reminder` rows and refills them
 * from a filtered subset of the server's own active set - and it withholds any row it cannot
 * attribute to a known local engine record, so an unattributable row VANISHES from the phone
 * rather than merely being unconfirmed. On Kevin's real household data this left 120 coursework
 * rows (`COSC 3334`/`COSC 3318`/`COSC 4305`) sitting on the server, never once rendered on the
 * phone. [EventsReconcile] is UNCHANGED by this file - it stays exactly as it is; retiring it is a
 * later slice, and having two mechanisms briefly coexist is fine (CLAUDE.md's own posture:
 * deleting a working mechanism before its replacement is proven is not).
 *
 * **The one rule every line of [pull] answers to: a local row the server does not have is left
 * alone.** Absence from the server is never evidence of deletion - the push side (uploading a
 * phone-only row TO the server) is a separate, later slice, so a row this device created and the
 * server has simply never seen yet must survive this merge completely untouched. Getting this
 * wrong would destroy the very rows [EventsReconcile] already correctly leaves in place today.
 *
 * **Matching a server row to a local one, and why guid/originGuid is checked BEFORE serverId
 * despite serverId reading first in plain English.** `service/LiveToolbox.kt`'s `addAppointment`
 * mints a FAKE, client-side `serverId` (`java.util.UUID.randomUUID()`) for every locally-authored
 * `kind = event` row - a value that has never touched the server and is not, and must never be
 * treated as, that row's real identity. The row's real, intentionally-minted sync identity is
 * [Event.guid] (mirrored server-side as [RemoteEvent.originGuid]) - set at creation and carried
 * forward unchanged by every later write, exactly the role [Event.guid]'s own doc comment
 * describes. Checking guid/originGuid FIRST means a locally-authored row is recognised as "this is
 * the same logical event" the moment the server learns about it under that same guid, rather than
 * only ever matching by pure coincidence on a serverId that was never real to begin with. serverId
 * is the fallback match, and it is a perfectly good one for any row that has ALREADY been through
 * one real round trip (a `kind = reminder` row written live through [NotesController]'s configured
 * path never carries a placeholder at all - [EventsBackend.upsert]'s own ack sets its serverId for
 * real at creation time - or a `kind = event` row this same merge, or [EventsReconcile], has
 * already matched once and corrected). **A locally-authored row carrying a fake serverId is
 * distinguished from a genuine server row by never being looked up BY that serverId in the first
 * place when a guid match is available** - not by inspecting the value itself (both are
 * syntactically identical UUID strings; there is no structural way to tell them apart, matching
 * [EventsReconcile]'s own documented limitation for the identical shape).
 *
 * **Every `kind` is handled, never silently dropped.** [EventsReconcile]'s own diff buckets rows
 * into `EVENT`/`REMINDER` by explicit equality, so a `task` row (or any future kind) matches
 * neither bucket and is quietly excluded - the exact failure CLAUDE.md's reconciliation-gate rule 6
 * names ("silently dropping a row you did not recognize is the same sin as accepting one you could
 * not verify"), applied here to a kind vocabulary instead of a statement line. This merge carries
 * an unrecognised kind straight through the ordinary insert/update/tombstone path - it only
 * appears, by name, in [PullReport.unrecognizedKinds] as information, never as an exclusion.
 *
 * **Idempotent by construction, not by convention.** [merged] rows are compared against the
 * existing local row by value before any write; an unchanged merge writes nothing and counts
 * nothing, so a second consecutive [pull] against the same server state is a genuine no-op both on
 * disk and in [PullReport]'s own counts (see [pull]'s inline comments for exactly where this
 * check sits on the tombstone and LWW branches).
 */
object EventsSync {

    /**
     * @param inserted server rows with no local match at all - the case that delivers the
     *   120 previously-invisible coursework rows.
     * @param updated local rows overwritten because the server's own `updated_at` was at least as
     *   new as the local row's (rule 4's LAST-WRITE-WINS, server wins on an exact tie - see
     *   [pull]'s own inline comment for why) AND the resulting row actually differs from what was
     *   already there (an unchanged tie writes nothing and is not counted here).
     * @param skippedLocalNewer local rows strictly newer than the server's version, left
     *   completely alone.
     * @param tombstoned server rows reporting `deleted_at` non-null, soft-deleted locally for the
     *   first time this run (an already-tombstoned local row is left alone and not re-counted).
     * @param unrecognizedKinds every distinct [RemoteEvent.kind] value seen this run that is not
     *   one of [EventKind]'s three known constants, sorted - reported, never silently dropped; the
     *   rows themselves are still merged normally.
     */
    data class PullReport(
        val inserted: Int,
        val updated: Int,
        val skippedLocalNewer: Int,
        val tombstoned: Int,
        val unrecognizedKinds: List<String>,
    )

    private val KNOWN_KINDS = setOf(EventKind.REMINDER, EventKind.EVENT, EventKind.TASK)

    /**
     * Pulls every active server event and merges it into the local `events` table. See this
     * object's own class doc for the full account of the matching order, the tombstone rule, and
     * why an unmatched local row is never touched.
     *
     * **No `Result` wrapper, per the brief's own signature.** A [backend.fetchActive] failure
     * throws straight out of this function - [maybeAutoPull] (this object's own foreground
     * trigger, below) is the layer responsible for catching it and degrading to a logged result;
     * a caller that wants a different failure posture is free to wrap this call in its own
     * try/catch, same as any other plain suspend function.
     */
    suspend fun pull(context: Context, backend: EventsBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val serverEvents = backend.fetchActive().getOrThrow()

        // getAll(), not getAllActive() - a server row must also be matched against a local row
        // that this device has ALREADY soft-deleted (deleted = 1), or a tombstoned local row would
        // look exactly like "no local match" below and get wrongly resurrected as a fresh insert.
        val localRows = db.eventDao().getAll()
        val localByGuid = localRows.filter { it.guid.isNotBlank() }.associateBy { it.guid }
        // Filtered to non-null (v58 -> v59, MIGRATION_58_59 widened Event.serverId to nullable for
        // the events-outbox ticket) - an outbox-pending row genuinely has no serverId yet, and more
        // than one can exist at once, so leaving nulls in would collapse them all onto one map entry
        // for a key `remote.serverId` (always non-null) could never match anyway. Harmless either
        // way since the lookup below is always keyed by a real server uuid, but filtering says so.
        val localByServerId = localRows.mapNotNull { row -> row.serverId?.let { it to row } }.toMap()

        var inserted = 0
        var updated = 0
        var skippedLocalNewer = 0
        var tombstoned = 0
        val unrecognizedKinds = sortedSetOf<String>()

        for (remote in serverEvents) {
            // Rule 7 (never silently drop an unrecognised kind) - reported here regardless of
            // which branch below this row ultimately takes.
            if (remote.kind !in KNOWN_KINDS) unrecognizedKinds += remote.kind

            // See this object's own class doc for why guid/originGuid is checked before serverId.
            val local = remote.originGuid?.let { localByGuid[it] } ?: localByServerId[remote.serverId]

            if (local == null) {
                // Rule 3: no local match at all -> INSERT. id = 0 lets Room autoincrement a fresh
                // local id - there is no engine ancestor to carry an id from (this is a live pull,
                // not EventsReconcile's one-time migration), the same "post-cutover row with no
                // engine ancestor" branch [EventsReconcile]'s own toReplica already documents.
                db.eventDao().insert(remote.toInsertedEvent())
                inserted++
                continue
            }

            if (remote.deleted) {
                // Rule 5: honour the tombstone with a local SOFT-delete, never a hard delete - the
                // row stays auditable, matching [Event.deleted]'s own soft-delete-mirror
                // convention. Guarded so a second pull of the same tombstone is a genuine no-op
                // (rule 8): a row already deleted = true locally is left untouched rather than
                // being written again with identical values.
                if (!local.deleted) {
                    db.eventDao().update(remote.toMergedEvent(local))
                    tombstoned++
                }
                continue
            }

            // Rule 4: LAST-WRITE-WINS on updatedAtMs. An EXACTLY EQUAL timestamp is resolved
            // toward the SERVER, not treated as a no-op and not resolved toward the local row -
            // this device has no push side yet, so the server is the only copy every other device
            // will ever converge on, and a tie is resolved toward that shared destination rather
            // than toward whichever write happened to reach this exact code path first.
            if (remote.updatedAtMs >= local.updatedAtMs) {
                val merged = remote.toMergedEvent(local)
                // Idempotency (rule 8): on a second consecutive pull of the same server state,
                // local.updatedAtMs already equals remote.updatedAtMs from THIS pull's own prior
                // write, so this branch runs again but produces a byte-for-byte identical [Event] -
                // skip the write and the count rather than reporting a phantom update.
                if (merged != local) {
                    db.eventDao().update(merged)
                    updated++
                }
            } else {
                // Rule 6's LWW-side twin: a local row strictly newer than the server's is left
                // completely alone.
                skippedLocalNewer++
            }
        }
        // Rule 6, the single most important line in this function BY OMISSION: nothing above ever
        // iterates localRows looking for a row to delete or flag. localByGuid/localByServerId are
        // read only to find a MATCH for a server row that showed up in serverEvents; a local row
        // the server does not have is never visited at all, let alone touched.

        return PullReport(
            inserted = inserted,
            updated = updated,
            skippedLocalNewer = skippedLocalNewer,
            tombstoned = tombstoned,
            unrecognizedKinds = unrecognizedKinds.toList(),
        )
    }

    /** A brand-new local row for a server event with no local match at all. */
    private fun RemoteEvent.toInsertedEvent(): Event = Event(
        id = 0,
        serverId = serverId,
        title = title,
        startsAt = startsAtMs,
        endsAt = endsAtMs,
        allDay = allDay,
        location = location,
        notes = notes,
        source = source,
        googleEventId = googleEventId,
        done = done,
        doneAt = doneAtMs,
        sortOrder = sortOrder,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDate = repeatEndDateMs,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAt = missedAtMs,
        missedDismissedAt = missedDismissedAtMs,
        loggedAt = loggedAtMs,
        updatedAtMs = updatedAtMs,
        deleted = deleted,
        createdAt = createdAtMs,
        kind = kind,
        structuredMeta = structuredMeta,
        // A fresh local row has no prior identity to preserve - reuse the server's own
        // migration-provenance guid when it states one (so a FUTURE pull or reconcile recognises
        // this exact row as already-present rather than treating it as a second copy), or mint a
        // new one. Same "ancestor-less row gets a fresh identity" rule [EventsReconcile]'s own
        // toReplica already establishes for the identical shape.
        guid = originGuid?.ifBlank { null } ?: UUID.randomUUID().toString(),
    )

    /** [existing] merged with [this] server row's fields - the LOCAL surrogate [Event.id] and,
     * where the server states none, [Event.guid] are the only two columns NOT simply replaced
     * wholesale by the server's own values. */
    private fun RemoteEvent.toMergedEvent(existing: Event): Event = Event(
        id = existing.id,
        serverId = serverId,
        title = title,
        startsAt = startsAtMs,
        endsAt = endsAtMs,
        allDay = allDay,
        location = location,
        notes = notes,
        source = source,
        googleEventId = googleEventId,
        done = done,
        doneAt = doneAtMs,
        sortOrder = sortOrder,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDate = repeatEndDateMs,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAt = missedAtMs,
        missedDismissedAt = missedDismissedAtMs,
        loggedAt = loggedAtMs,
        updatedAtMs = updatedAtMs,
        deleted = deleted,
        createdAt = createdAtMs,
        kind = kind,
        structuredMeta = structuredMeta,
        // Preserve the row's OWN existing guid on an update rather than regenerating one - this is
        // exactly what lets serverId matching become trustworthy on the NEXT pull for a row that
        // only matched via guid this time (see this file's own class doc), and it leaves a
        // `kind = reminder` row's guid untouched when the server states no originGuid for it
        // (never populated by that kind's own live write path in the first place - see
        // [Event.guid]'s own doc comment - so there is nothing here to overwrite).
        guid = originGuid ?: existing.guid,
    )

    // --- Foreground auto-trigger ---------------------------------------------------------------

    /** Sync-adjacent process work, same posture as [com.kevin.legion.sync.SyncEngine]'s own
     * `engineScope` - a pass that survives the launching Activity is the desired behaviour, and
     * [pull] has no cancellation-sensitive side effect that would need one tied to a screen. */
    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastAutoPullAt = 0L

    /** Mirrors [com.kevin.legion.sync.SyncEngine]'s own 5-minute foreground throttle - `onResume`
     * fires on essentially every foreground return, and a pull is a real network round trip. */
    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * `MainActivity.onResume`'s hook. **Deliberately separate from
     * [com.kevin.legion.sync.SyncEngine.maybeAutoSync]** - that engine is a distinct Drive-JSON
     * mechanism that has never once executed on a real device (`memory/MEMORY.md`), and this
     * ticket's brief is explicit that the two must not be entangled. No-ops silently, with a
     * logged breadcrumb rather than a dialog or a crash, when Supabase is not configured yet or
     * nobody is signed in - both ordinary states on a fresh or half-set-up install. Fire-and-forget
     * on [autoPullScope]; this function itself never suspends and never touches the UI thread.
     */
    fun maybeAutoPull(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoPullAt < AUTO_PULL_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        // SupabaseAuth.currentUserId()'s own doc comment names a real cold-start race: it can read
        // null for a signed-in account while the session restore is still in flight. Accepted here
        // rather than awaited - a false negative just means this pull runs on the NEXT resume
        // instead, never a crash or a hang, and awaiting would turn a fire-and-forget hook into one
        // that blocks its own launch on a session restore.
        if (SupabaseAuth(app).currentUserId() == null) return
        lastAutoPullAt = now
        autoPullScope.launch {
            try {
                val report = pull(app, SupabaseEventsBackend(client))
                MidnightEvents.eventsAutoPullSucceeded(
                    report.inserted,
                    report.updated,
                    report.skippedLocalNewer,
                    report.tombstoned,
                    report.unrecognizedKinds,
                )
            } catch (e: Exception) {
                MidnightEvents.eventsAutoPullFailed(e)
            }
        }
    }
}
