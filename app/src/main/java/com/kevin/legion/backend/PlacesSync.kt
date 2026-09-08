package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.backend.engine.guardingForeground
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The install-scoped high-water mark for [PlacesSync.pull] - one watermark, because `places` is
 * one table, unlike [BodyPullCursor]'s eight.
 *
 * **A missing watermark means "fetch everything", never "fetch nothing"** - same [EventsPullCursor]
 * and [BodyPullCursor] guarantee, same reasoning: it defaults to 0 (1970-01-01) and every real
 * row's `updated_at` is `>= 0`, so a fresh install's first pull asks for the whole table. That is
 * also what makes this the restore path after a wipe: the phone with no places asks for everything
 * and gets everything.
 */
internal object PlacesPullCursor {
    private const val PREFS = "places_pull_cursor"
    private const val KEY = "places"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAtMs(context: Context): Long = prefs(context).getLong(KEY, 0L)

    /** Persisted only after [PlacesSync.pull] has fully merged its batch - advancing on a partial
     * run would skip whatever the failed half of the batch contained, for good. */
    fun advance(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY, atMs).apply()
    }
}

/**
 * **The server-to-phone half of `places`, which did not exist until now.**
 *
 * Found on the A25 on 2026-09-07: a place inserted directly on the engine never reached the phone,
 * across SYNC NOW, a re-entry into the screen and a cold start. Places had a write path
 * ([com.kevin.legion.location.PlaceController], pure write-through on a server ACK) and a Supabase-era
 * migration pass ([PlacesReconcile]) and nothing else - and [PlacesReconcile.maybeAutoRun] returns
 * early by design when this aspect is on [Transport.DJANGO] (see its own comment for why: it reads
 * the retired on-device engine store and would push those rows into a project the phone has stopped
 * reading). Correct, and nothing replaced it. This is the replacement, and it is a different thing
 * entirely: a merge pull, not a migration upload.
 *
 * **Django only, by construction rather than by a guard.** The pull needs
 * [PlacesIncrementalPull] - a `?since=` feed carrying tombstones - and only
 * [com.kevin.legion.backend.engine.DjangoPlacesBackend] implements it, so [maybeAutoPull]'s `as?`
 * resolves to null on Supabase and the pull cannot run there. An install that has not flipped the
 * `places` transport row behaves byte-for-byte as it did before this file existed:
 * [PlacesReconcile] still runs, this does not.
 *
 * **The merge rules are [BodyMerge.merge]'s, not a second copy of them.** That function is generic
 * over its accessor lambdas and is the shape body proved on hardware; reusing it here means the
 * "a local row the server does not have is left alone" rule (its rule 6, the one that keeps a place
 * tagged offline from being deleted by a pull) is honoured by the same code that honours it for
 * body's eight tables, rather than by a seventh transcription of the same six branches. The name
 * reads oddly from this file and that is the smaller cost.
 *
 * **No outbox and no drain, deliberately** - [com.kevin.legion.location.PlaceController] is pure
 * write-through with no queue on either transport (see [PlacesBackend]'s own class doc), so there
 * is nothing to drain BEFORE this pull the way [BodyOutboxDrain] must run before [BodySync]. The
 * ordering argument every other aspect makes simply has no subject here.
 */
object PlacesSync {

    /** What one [pull] did - the same five counters [BodySync.PullReport] carries, so a report from
     * either aspect reads the same way in a log line. */
    data class PullReport(
        val inserted: Int,
        val updated: Int,
        val skippedLocalNewer: Int,
        val tombstoned: Int,
        val skippedTombstoneNoLocalMatch: Int,
    )

    /**
     * Pulls everything changed since the watermark and merges it into the [TaggedPlace] replica.
     *
     * **Matched on `label`, which is the natural key on BOTH sides** - `places.label_unique`
     * server-side, `@PrimaryKey val label` in Room - so unlike [EventsSync.pull] there is no
     * serverId fallback branch and no placeholder-guid case. It is also why insert and update are
     * the same call here: `PlaceDao.upsert` is `OnConflictStrategy.REPLACE` on that same key.
     *
     * The local LWW clock is [TaggedPlace.timestamp], which that column's own doc comment already
     * names as this table's clock ("flipping `deleted` + bumping `timestamp`... makes the deletion
     * a normal LWW fact"). The remote clock is the server's `updated_at`. Server wins an exact tie,
     * per [BodyMerge]'s rule.
     *
     * Throws on a fetch failure rather than reporting a partial pass - same posture as
     * [BodySync.pull] - leaving the watermark untouched so the next run asks for the same window.
     */
    suspend fun pull(context: Context, backend: PlacesIncrementalPull): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = PlacesPullCursor.lastPulledAtMs(context)
        val remote = backend.fetchChangedSince(sinceMs).getOrThrow()
        // Tombstones included on BOTH sides - see PlaceDao.getAllIncludingTombstones for why the
        // ordinary getAll() would quietly break the tombstone branch.
        val local = db.placeDao().getAllIncludingTombstones()
        val report = BodyMerge.merge(
            remoteRows = remote,
            localRows = local,
            remoteGuid = { it.label },
            localGuid = { it.label },
            remoteDeleted = { it.deleted },
            remoteUpdatedAtMs = { it.updatedAtMs },
            localUpdatedAtMs = { it.timestamp },
            localDeleted = { it.deleted },
            toInserted = { r ->
                TaggedPlace(
                    label = r.label,
                    latitude = r.latitude,
                    longitude = r.longitude,
                    timestamp = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            toMerged = { r, existing ->
                // `label` is the primary key and is what these two were matched ON, so it is
                // already equal; copying the other four columns is the whole row.
                existing.copy(
                    latitude = r.latitude,
                    longitude = r.longitude,
                    timestamp = r.updatedAtMs,
                    deleted = r.deleted,
                )
            },
            withDeletedFlag = { existing, atMs -> existing.copy(deleted = true, timestamp = atMs) },
            // Both branches are the same call: REPLACE on the label primary key inserts when there
            // is no row and overwrites when there is, which is exactly the pair BodyMerge wants.
            insert = { db.placeDao().upsert(it) },
            update = { db.placeDao().upsert(it) },
        )
        remote.maxOfOrNull { it.updatedAtMs }?.let { PlacesPullCursor.advance(context, it) }
        return PullReport(
            inserted = report.inserted,
            updated = report.updated,
            skippedLocalNewer = report.skippedLocalNewer,
            tombstoned = report.tombstoned,
            skippedTombstoneNoLocalMatch = report.skippedTombstoneNoLocalMatch,
        )
    }

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastAutoPullAt = 0L

    /** Same five-minute floor every sibling `maybeAutoPull` uses. It is the throttle, and on this
     * aspect it is also the whole freshness story - see [maybeAutoPull]'s own doc comment for why
     * `places` deliberately does NOT join [com.kevin.legion.backend.engine.EnginePoll]. */
    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** Test seam for the throttle's own arithmetic, and the reset a test needs between methods -
     * [lastAutoPullAt] is process-scoped state on an `object`, which Robolectric does not reset. */
    internal fun setLastAutoPullAtForTest(atMs: Long) {
        lastAutoPullAt = atMs
    }

    /**
     * The backend to pull with, or null when this run should not happen at all: throttled, not on
     * the engine, or no engine reachable from this device.
     *
     * **The throttle slot is spent only when a real run follows.** Resolving the backend before
     * claiming the slot is what stops a device with no token from burning it - claiming first would
     * make the first real pull after a sign-in wait five minutes for no reason.
     *
     * The three conditions are one expression rather than three early returns purely to stay under
     * detekt's `ReturnCount`; the reading order is unchanged.
     */
    private fun dueBackend(app: Context, now: Long): PlacesIncrementalPull? {
        val throttled = now - lastAutoPullAt < AUTO_PULL_MIN_INTERVAL_MS
        val onDjango = EngineTransport(app).transportFor(EngineBackends.ASPECT_PLACES) == Transport.DJANGO
        val backend = if (throttled || !onDjango) {
            null
        } else {
            EngineBackends(app).placesBackend() as? PlacesIncrementalPull
        }
        if (backend != null) lastAutoPullAt = now
        return backend
    }

    /**
     * `MainActivity.onResume`'s hook - a cold start and every foreground return, throttled to five
     * minutes. Fire-and-forget on its own scope; never suspends the caller. Fails to a
     * [MidnightEvents] breadcrumb, never a dialog and never a crash, same posture as every sibling.
     *
     * **`places` deliberately does NOT join [com.kevin.legion.backend.engine.EnginePoll.COVERED_ASPECTS],
     * and this is the decision the brief asked to be stated.** That poll is what replaces Supabase
     * Realtime for a Django aspect - its own class doc says so in the first line - and Realtime is
     * bought when a change made elsewhere has to arrive without waiting for a foreground return.
     * Weigh that for this aspect specifically: a saved place is tagged a handful of times a year,
     * both limbs of the household share the same short list, and the only reader on a hot path
     * ([com.kevin.legion.location.PlaceController.currentLabel], the geofence and the assistant's
     * "where am I") reads the Room replica and must keep working with no network at all. A
     * 60-second poll would spend a request a minute, forever, to notice something that changes
     * annually - and it would notice it while the app is open, which is exactly when this hook has
     * already run. **A cold-start-and-resume pull is the honest answer**, and it is the mechanism
     * that demonstrably delivered body's server-side change on the A25 rather than the one that
     * did not.
     *
     * **No Supabase session resolve on this path, unlike [BodySync.maybeAutoPull]'s branch.** There
     * is no Supabase branch to gate: [PlacesIncrementalPull] is implemented only by the Django
     * backend, so by the time [pull] is reachable the transport is already the engine, and an
     * engine device has no Supabase session to restore.
     */
    fun maybeAutoPull(context: Context) {
        val app = context.applicationContext
        val backend = dueBackend(app, System.currentTimeMillis()) ?: return
        autoPullScope.launch {
            // guardingForeground rather than a bare try/catch: this runs on a SupervisorJob scope
            // with no CoroutineExceptionHandler, so an uncaught throw reaches the thread's default
            // handler and takes the app down on something as ordinary as unlocking the screen. The
            // sibling aspects' own `maybeAutoPull` bodies catch exactly this broadly for exactly
            // this reason and sit in detekt's baseline for it; this file uses the shared helper
            // that already carries the suppression and the argument for it.
            guardingForeground(onFailure = { MidnightEvents.placesAutoPullFailed(it) }) {
                val report = pull(app, backend)
                MidnightEvents.placesAutoPullSucceeded(
                    report.inserted, report.updated, report.skippedLocalNewer,
                    report.tombstoned, report.skippedTombstoneNoLocalMatch,
                )
            }
        }
    }
}
