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
 * Install-scoped high-water mark for [EventsSync.pull] - the server `updated_at` (epoch ms) this
 * device has already pulled up to. Same shape and reasoning as [ObdSampleUploadCursor] (plain
 * [android.content.SharedPreferences], install-scoped, never synced), applied to a PULL watermark
 * instead of an upload one: [EventsBackend.fetchChangedSince] takes this value so a routine pull
 * (the expected steady state) asks the server for only what changed since last time, rather than
 * re-fetching and re-diffing the whole `events` table on every foreground return or realtime tick.
 *
 * **A missing watermark means "fetch everything", never "fetch nothing".** [lastPulledAtMs]
 * defaults to 0 (1970-01-01), and every real row's `updated_at` is >= that - so a fresh install
 * with no prior pull asks [EventsBackend.fetchChangedSince] for the entire table, exactly the
 * CLAUDE.md-flagged failure shape this brief calls out by name ("a watermark bug that silently
 * syncs zero rows"). There is no separate "never pulled" sentinel to get wrong.
 */
internal object EventsPullCursor {
    private const val PREFS = "events_pull_cursor"
    private const val KEY_LAST_PULLED_AT_MS = "last_pulled_updated_at_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The server `updated_at` (epoch ms) this device has already pulled up to, or 0 if this
     *  device has never run a successful pull - see this object's own doc for why 0, not a null
     *  sentinel, is what makes "never pulled" behave as "fetch everything". */
    fun lastPulledAtMs(context: Context): Long = prefs(context).getLong(KEY_LAST_PULLED_AT_MS, 0L)

    /** Persisted only after [EventsSync.pull] has fully processed a batch - see that function's
     *  own call site comment for why advancing on a partial run would be wrong. */
    fun advance(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_PULLED_AT_MS, atMs).apply()
    }
}

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
     *   120 previously-invisible coursework rows. **Never includes a tombstoned row with no local
     *   match** - see [skippedTombstoneNoLocalMatch] for why that case is its own bucket, not this
     *   one and not [tombstoned].
     * @param updated local rows overwritten because the server's own `updated_at` was at least as
     *   new as the local row's (rule 4's LAST-WRITE-WINS, server wins on an exact tie - see
     *   [pull]'s own inline comment for why) AND the resulting row actually differs from what was
     *   already there (an unchanged tie writes nothing and is not counted here).
     * @param skippedLocalNewer local rows strictly newer than the server's version, left
     *   completely alone.
     * @param tombstoned server rows reporting `deleted_at` non-null **that matched an existing
     *   local row**, soft-deleted locally for the first time this run (an already-tombstoned local
     *   row is left alone and not re-counted).
     * @param skippedTombstoneNoLocalMatch server rows reporting `deleted_at` non-null with NO
     *   local match at all - traced 2026-09-02 on the A25: a routine pull inserted 88 such rows as
     *   fresh, locally-tombstoned dead weight (`deleted = 1` on a row this phone had never held),
     *   a bug that would keep growing on every server-side deletion from here. A tombstone exists
     *   to mark something the local database HAS; one with nothing to mark is inserted nowhere and
     *   counted here, never in [inserted] (it was never really an insert) and never in [tombstoned]
     *   (nothing was actually deleted here - there was nothing to delete).
     * @param unrecognizedKinds every distinct [RemoteEvent.kind] value seen this run that is not
     *   one of [EventKind]'s three known constants, sorted - reported, never silently dropped; the
     *   rows themselves are still merged normally.
     */
    data class PullReport(
        val inserted: Int,
        val updated: Int,
        val skippedLocalNewer: Int,
        val tombstoned: Int,
        val skippedTombstoneNoLocalMatch: Int,
        val unrecognizedKinds: List<String>,
    )

    private val KNOWN_KINDS = setOf(EventKind.REMINDER, EventKind.EVENT, EventKind.TASK)

    /**
     * Pulls every server event changed since [EventsPullCursor]'s own watermark - active or
     * tombstoned - and merges it into the local `events` table. See this object's own class doc
     * for the full account of the matching order, the tombstone rule, and why an unmatched local
     * row is never touched.
     *
     * **`fetchChangedSince`, not `fetchActive` - the fix for the tombstone-propagation gap traced
     * 2026-09-02.** `fetchActive`'s own server-side `deleted_at IS NULL` filter meant a row
     * soft-deleted on another device (or the web app) was never in what THIS device's pull saw at
     * all - the [merged]/tombstone branch below was correct but unreachable end to end. Reading
     * with a watermark closes that gap and is also what makes a routine pull CHEAP (only what
     * changed, not the whole table) rather than the two being unrelated changes bolted together.
     *
     * **The watermark is advanced ONLY after every row in this batch has actually been merged**,
     * to the max `updatedAtMs` seen in [serverEvents] - never before the loop, and never to
     * `System.currentTimeMillis()` (this device's clock, not the server's, and a row could still
     * be mid-write server-side at a timestamp between "call started" and "call returned"). A
     * [backend.fetchChangedSince] failure throws straight out of this function BEFORE the
     * watermark is read into a local variable that could get written back stale - see
     * [maybeAutoPull] and [EventsRealtime], the two callers responsible for catching it and
     * degrading to a logged result; a caller that wants a different failure posture is free to
     * wrap this call in its own try/catch, same as any other plain suspend function.
     */
    suspend fun pull(context: Context, backend: EventsBackend): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = EventsPullCursor.lastPulledAtMs(context)
        val serverEvents = backend.fetchChangedSince(sinceMs).getOrThrow()

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
        var skippedTombstoneNoLocalMatch = 0
        val unrecognizedKinds = sortedSetOf<String>()

        for (remote in serverEvents) {
            // Rule 7 (never silently drop an unrecognised kind) - reported here regardless of
            // which branch below this row ultimately takes.
            if (remote.kind !in KNOWN_KINDS) unrecognizedKinds += remote.kind

            // See this object's own class doc for why guid/originGuid is checked before serverId.
            val local = remote.originGuid?.let { localByGuid[it] } ?: localByServerId[remote.serverId]

            if (local == null && remote.deleted) {
                // Traced 2026-09-02 (the 88-row bug, see PullReport.skippedTombstoneNoLocalMatch's
                // own doc comment): a tombstone with nothing local to mark is not an insert and not
                // a tombstone-applied - it is inserted nowhere. Checked BEFORE the plain
                // "no local match" branch below so this case never falls into it and gets written
                // as a brand-new, already-dead local row.
                skippedTombstoneNoLocalMatch++
                continue
            }

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

        // Advance the watermark to the newest updated_at this batch actually saw - see this
        // function's own doc comment for why this happens here (after every row is merged) and
        // not before the loop or off the local clock. An empty batch leaves the cursor untouched,
        // which is already correct: nothing changed server-side since last time, so there is
        // nothing to advance past.
        serverEvents.maxOfOrNull { it.updatedAtMs }?.let { newestSeen ->
            EventsPullCursor.advance(context, newestSeen)
        }

        return PullReport(
            inserted = inserted,
            updated = updated,
            skippedLocalNewer = skippedLocalNewer,
            tombstoned = tombstoned,
            skippedTombstoneNoLocalMatch = skippedTombstoneNoLocalMatch,
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

    /** Gap before the one retry [SupabaseAuth.resolveSignedInUserId] takes - kept here as the
     *  default for [resolveUserIdForAutoPull] below so existing callers and tests naming this
     *  constant are undisturbed. See [SupabaseAuth.resolveSignedInUserId]'s own doc comment for
     *  the mechanism and the reasoning behind the value. */
    private const val AUTO_PULL_RETRY_DELAY_MS = 1_000L

    /**
     * **Thin delegation, not an implementation (2026-09-02).** This used to hold its own copy of
     * the bounded-retry logic; that copy was hand-duplicated into [BodySync.resolveUserIdForAutoPull]
     * and then never propagated to the six OTHER callers still using a raw [SupabaseAuth.currentUserId]
     * guard, which is exactly the bug this pass fixed. The one retry now lives in
     * [SupabaseAuth.resolveSignedInUserId] - see that method's own doc comment for the mechanism,
     * and [maybeAutoPull]'s doc comment for why a single retry replaced the old
     * accept-and-wait-for-next-resume posture.
     *
     * `internal`, not `private`, so `EventsSyncTest` can drive the retry directly against a
     * fake [SupabaseAuthGateway] (via [SupabaseAuth]'s own test seam) without needing a real
     * [SupabaseClientProvider]-backed client, which nothing in this test environment has.
     */
    internal suspend fun resolveUserIdForAutoPull(
        auth: SupabaseAuth,
        retryDelayMs: Long = AUTO_PULL_RETRY_DELAY_MS,
    ): String? = auth.resolveSignedInUserId(retryDelayMs)

    /**
     * `MainActivity.onResume`'s hook. **Deliberately separate from
     * [com.kevin.legion.sync.SyncEngine.maybeAutoSync]** - that engine is a distinct Drive-JSON
     * mechanism that has never once executed on a real device (`memory/MEMORY.md`), and this
     * ticket's brief is explicit that the two must not be entangled. No-ops silently, with a
     * logged breadcrumb rather than a dialog or a crash, when Supabase is not configured yet or
     * nobody is signed in - both ordinary states on a fresh or half-set-up install. Fire-and-forget
     * on [autoPullScope]; this function itself never suspends and never touches the UI thread.
     *
     * **The cold-start guard used to read [SupabaseAuth.currentUserId] synchronously and just
     * return on null, on the reasoning that a false negative only costs the NEXT resume - see
     * that method's own doc comment, corrected 2026-09-02.** Observed on the A25 the same day:
     * force-stop, launch, wait 16 seconds - no pull, nothing in logcat at all; background and
     * re-foreground the SAME app and `events_auto_pull inserted=89` fires immediately. The
     * reasoning was right about the mechanism and wrong about the cost - for a phone someone
     * checks once and pockets, "wait for the next resume" often means "never", not "slightly
     * later". The throttle slot is still reserved synchronously, right here, before any awaiting
     * happens - that is what keeps a second `onResume` firing mid-restore (background/foreground
     * while this coroutine is still waiting) from launching a second pull; see
     * [resolveUserIdForAutoPull] for the bounded, single-retry wait that replaced the bare
     * `currentUserId()` read.
     */
    fun maybeAutoPull(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoPullAt < AUTO_PULL_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoPullAt = now
        autoPullScope.launch {
            try {
                val userId = resolveUserIdForAutoPull(SupabaseAuth(app))
                if (userId == null) return@launch
                val report = pull(app, SupabaseEventsBackend(client))
                MidnightEvents.eventsAutoPullSucceeded(
                    report.inserted,
                    report.updated,
                    report.skippedLocalNewer,
                    report.tombstoned,
                    report.unrecognizedKinds,
                    report.skippedTombstoneNoLocalMatch,
                )
            } catch (e: Exception) {
                MidnightEvents.eventsAutoPullFailed(e)
            }
        }
    }
}
