package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.backend.engine.guardingForeground
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The install-scoped high-water mark for [VoiceNotesSync.pull]. One watermark, one table - see
 * [PlacesPullCursor], which this mirrors exactly, and [BodyPullCursor] for the eight-watermark
 * case. A missing value means "fetch everything", never "fetch nothing".
 */
internal object VoiceNotesPullCursor {
    private const val PREFS = "voice_notes_pull_cursor"
    private const val KEY = "voice_notes"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAtMs(context: Context): Long = prefs(context).getLong(KEY, 0L)

    /** Persisted only after [VoiceNotesSync.pull] has fully merged its batch. */
    fun advance(context: Context, atMs: Long) {
        prefs(context).edit().putLong(KEY, atMs).apply()
    }
}

/**
 * **The server-to-phone half of `voice_notes`, which did not exist at all until now.**
 *
 * `DjangoVoiceNotesBackend.fetchChangedSince` was built and tested with NO CALLER - its own doc
 * comment said so in writing - and there was no `VoiceNotesSync` of any kind, so nothing ever
 * merged a voice note into Room from anywhere but the microphone on this phone. Found on the A25,
 * 2026-09-07, alongside the same gap in `places`.
 *
 * **Not Realtime, and not the 60-second poll either.** Kevin, 2026-09-04: *"voice notes would be
 * via sync not realtime. keep on device first"* (`library/decisions.md`, "Voice notes sync, but not
 * Realtime, and the device copy stays primary"). `EnginePoll` IS the Realtime replacement for a
 * Django aspect - its own class doc opens with that sentence - so putting `voice_notes` in
 * [com.kevin.legion.backend.engine.EnginePoll.COVERED_ASPECTS] would re-adopt by the back door the
 * mechanism that ruling declines. A cold start and every foreground return, throttled, is the whole
 * mechanism, and it is what that decision asks for.
 *
 * **The device copy is primary, and here that is a merge rule rather than a slogan.** See
 * [mergeInto] for the three branches and for the one thing this pull deliberately will not do.
 *
 * **The audio is untouched and untouchable by this path.** [RemoteVoiceNote] has no audio field,
 * `public.voice_notes` has no such column, and [VoiceNotesIncrementalPull] returns nothing else -
 * so a pull can put text on this phone and can never take a recording off one. The only file this
 * class ever touches is one it DELETES, through [VoiceNoteStore], on ADR 0041's cascade.
 *
 * **Django only, by construction.** The pull needs [VoiceNotesIncrementalPull] and only
 * [com.kevin.legion.backend.engine.DjangoVoiceNotesBackend] implements it, so [maybeAutoPull]'s
 * `as?` resolves to null on Supabase. An install that has not flipped the `voice_notes` transport
 * row behaves byte-for-byte as it did before this file existed.
 */
object VoiceNotesSync {

    /** What one [pull] did. Five counters, and [skippedNoTranscript] is the one no sibling aspect
     * has - see [mergeInto]'s insert branch for what it counts and why that row is not written. */
    data class PullReport(
        val inserted: Int = 0,
        val updated: Int = 0,
        val tombstoned: Int = 0,
        val skippedTombstoneNoLocalMatch: Int = 0,
        val skippedNoTranscript: Int = 0,
    )

    /**
     * Pulls everything changed since the watermark and merges it into the [VoiceNote] table.
     *
     * Throws on a fetch failure rather than reporting a partial pass - same posture as
     * [BodySync.pull] and [PlacesSync.pull] - leaving the watermark untouched so the next run asks
     * for the same window again.
     */
    suspend fun pull(context: Context, backend: VoiceNotesIncrementalPull): PullReport {
        val db = CarDatabase.getDatabase(context)
        val sinceMs = VoiceNotesPullCursor.lastPulledAtMs(context)
        val remote = backend.fetchChangedSince(sinceMs).getOrThrow()
        val report = mergeInto(context, remote)
        remote.maxOfOrNull { it.updatedAtMs }?.let { VoiceNotesPullCursor.advance(context, it) }
        return report
    }

    /**
     * The merge, written out by hand rather than delegating to [BodyMerge.merge], and the reason is
     * structural rather than stylistic: **`voice_notes` has no local tombstone column and no local
     * clock**, and [BodyMerge] needs both.
     *
     * - It routes its tombstone branch through the same `update` lambda as its last-write-wins
     *   branch, carrying a `deleted` flag. [VoiceNote] has no such flag: a local delete is a HARD
     *   delete of the row plus the `.m4a` beside it ([VoiceNoteStore.delete], ADR 0041's "retained
     *   or destroyed together"). Handing [BodyMerge] an `update` that deletes would delete on the
     *   ordinary merge branch too.
     * - It resolves conflicts on `localUpdatedAtMs`. [VoiceNote] has no `updatedAt` column at all,
     *   so there is no clock on this side to weigh the server's against.
     *
     * **The four branches:**
     *
     * 1. **Server tombstone, local row present** - [VoiceNoteStore.delete]: the row, the transcript
     *    and the summary (both columns ON that row), and the audio file. ADR 0041's cascade, run by
     *    the one function that already owns it.
     * 2. **Server tombstone, no local row** - skipped entirely. Never inserted as dead weight.
     * 3. **Server row this phone does not have** - inserted, with `audioPath = null`, because the
     *    recording is on whichever device made it and this pull cannot carry a file.
     *    **UNLESS the server row has no transcript**, in which case it is skipped and counted in
     *    [PullReport.skippedNoTranscript]: [VoiceNote]'s own contract is that `audioPath` is never
     *    null while `transcript` is null ("the audio is the only evidence and must still be on
     *    disk"), so a row with neither would be a recording this phone claims to hold and has no
     *    trace of - exactly the empty claim ADR 0041's anchor chain forbids. It is not lost: the
     *    transcript landing on the other device bumps that row's `updated_at`, so the next pull
     *    past this watermark serves it again, with its anchor.
     * 4. **Server row this phone already has** - **fills nulls only. A non-null local value is
     *    never replaced.**
     *
     * **Branch 4 is a decision, and it is the conservative one.** "The device copy is primary" is
     * Kevin's ruling for this aspect, and with no local clock there is no honest way to tell a
     * server edit that is newer from one that is older. Filling nulls can only ever add information
     * (a transcript or summary that finished on another limb arrives here) and can never destroy
     * any: a title renamed on this phone, or a transcript whose push failed, survives a pull
     * unchanged. **The cost, stated plainly: a note RENAMED server-side does not overwrite a title
     * this phone already has.** That is the honest price of having no clock, not an oversight, and
     * the fix is a real one rather than a tweak here - an `updatedAt` column on [VoiceNote] (a
     * schema bump, its own migration and test) or an outbox for the rename. Neither is in this
     * ticket's scope, and choosing server-wins without one would mean a pull could silently revert
     * a rename made while the engine was unreachable.
     */
    internal suspend fun mergeInto(context: Context, remoteRows: List<RemoteVoiceNote>): PullReport {
        val dao = CarDatabase.getDatabase(context).voiceNoteDao()
        val store = VoiceNoteStore(dao)
        // Only rows that have ever reached the server can be matched by one coming back from it;
        // a local row with a null serverId has no key a remote row could collide with. Filtering
        // here rather than mapping nulls to a sentinel keeps that fact visible.
        val localByServerId = dao.getAll().mapNotNull { note ->
            note.serverId?.let { it to note }
        }.toMap()

        var report = PullReport()
        for (remote in remoteRows) {
            val local = localByServerId[remote.serverId]
            report = when {
                remote.deleted && local == null ->
                    report.copy(skippedTombstoneNoLocalMatch = report.skippedTombstoneNoLocalMatch + 1)

                remote.deleted -> {
                    // ADR 0041's cascade: row, transcript, summary and the .m4a, together.
                    store.delete(local!!.id)
                    report.copy(tombstoned = report.tombstoned + 1)
                }

                local == null && remote.transcript == null ->
                    report.copy(skippedNoTranscript = report.skippedNoTranscript + 1)

                local == null -> {
                    dao.insert(remote.toLocalRow())
                    report.copy(inserted = report.inserted + 1)
                }

                else -> {
                    val merged = local.fillingNullsFrom(remote)
                    if (merged == local) report else {
                        dao.update(merged)
                        report.copy(updated = report.updated + 1)
                    }
                }
            }
        }
        // By omission, and deliberately: nothing above ever iterates the LOCAL rows looking for one
        // to delete. localByServerId is read only to find a match for a row the server sent, so a
        // note recorded on this phone and not yet pushed is never touched by a pull.
        return report
    }

    /** A server row this phone has never seen, as a local row. `audioPath` is null because the
     * recording is on the device that made it and no pull can carry a file - and `transcript` is
     * guaranteed non-null by the caller's own branch, so [VoiceNote]'s "audio is never null while
     * transcript is null" contract holds. `id = 0` lets Room mint the local surrogate key. */
    private fun RemoteVoiceNote.toLocalRow() = VoiceNote(
        id = 0,
        serverId = serverId,
        startedAt = startedAtMs,
        endedAt = endedAtMs,
        title = title,
        summary = summary,
        transcript = transcript,
        audioPath = null,
        kind = kind,
        provenance = provenance,
        interrupted = interrupted,
    )

    /**
     * [remote]'s values for every column this phone has left null, and this phone's own value for
     * every column it has filled - see [mergeInto]'s branch 4 for why that direction and not the
     * other.
     *
     * Four columns are never touched from here at all: [VoiceNote.audioPath] (local truth about
     * local disk, and the server has no opinion to offer), [VoiceNote.id] (the local surrogate
     * key), and [VoiceNote.transcriptionFailureReason]/[VoiceNote.transcriptionAttemptStartedAt]
     * (this device's own record of what its own transcription attempt did, which no other device
     * can know anything about). [VoiceNote.kind], [VoiceNote.startedAt] and [VoiceNote.interrupted]
     * are non-nullable and fixed at recording time by whichever device held the microphone, so
     * there is nothing for a pull to fill.
     */
    private fun VoiceNote.fillingNullsFrom(remote: RemoteVoiceNote) = copy(
        endedAt = endedAt ?: remote.endedAtMs,
        title = title ?: remote.title,
        summary = summary ?: remote.summary,
        transcript = transcript ?: remote.transcript,
    )

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastAutoPullAt = 0L

    /** Same five-minute floor every sibling `maybeAutoPull` uses. */
    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** Test seam for the throttle - [lastAutoPullAt] is process-scoped state on an `object`, which
     * Robolectric does not reset between test methods. */
    internal fun setLastAutoPullAtForTest(atMs: Long) {
        lastAutoPullAt = atMs
    }

    /** The backend to pull with, or null when this run should not happen at all - see
     * [PlacesSync]'s own `dueBackend`, which this mirrors exactly, for why the slot is claimed
     * after the backend resolves and not before. */
    private fun dueBackend(app: Context, now: Long): VoiceNotesIncrementalPull? {
        val throttled = now - lastAutoPullAt < AUTO_PULL_MIN_INTERVAL_MS
        val onDjango =
            EngineTransport(app).transportFor(EngineBackends.ASPECT_VOICE_NOTES) == Transport.DJANGO
        val backend = if (throttled || !onDjango) {
            null
        } else {
            EngineBackends(app).voiceNotesBackend() as? VoiceNotesIncrementalPull
        }
        if (backend != null) lastAutoPullAt = now
        return backend
    }

    /**
     * `MainActivity.onResume`'s hook - a cold start and every foreground return, throttled to five
     * minutes, fire-and-forget on its own scope. Fails to a [MidnightEvents] breadcrumb, never a
     * dialog and never a crash.
     *
     * See this object's class doc for why `voice_notes` does NOT join
     * [com.kevin.legion.backend.engine.EnginePoll.COVERED_ASPECTS]: that poll is the Realtime
     * replacement, and Kevin's 2026-09-04 ruling for this aspect is sync and not Realtime.
     */
    fun maybeAutoPull(context: Context) {
        val app = context.applicationContext
        val backend = dueBackend(app, System.currentTimeMillis()) ?: return
        autoPullScope.launch {
            // See PlacesSync.maybeAutoPull's own comment: a SupervisorJob scope with no
            // CoroutineExceptionHandler makes an uncaught throw fatal, and guardingForeground is
            // the shared helper that already carries the suppression and the argument for it.
            guardingForeground(onFailure = { MidnightEvents.voiceNotesAutoPullFailed(it) }) {
                val report = pull(app, backend)
                MidnightEvents.voiceNotesAutoPullSucceeded(
                    report.inserted, report.updated, report.tombstoned,
                    report.skippedTombstoneNoLocalMatch, report.skippedNoTranscript,
                )
            }
        }
    }
}
