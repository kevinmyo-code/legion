package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.backend.engine.engineRefusalSentence
import com.kevin.legion.backend.engine.guardingForeground
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.voice.VoiceNoteController

/**
 * Install-scoped high-water mark for [VoiceNotesBackfill], plus the rows the engine will never
 * take. `voice_notes` DOES have an autoincrement `id`, unlike `places`, so this is the ordinary
 * `Long` cursor [ChecklistsBackfillCursor] and [LastAspectsBackfillCursor] use rather than
 * [PlacesBackfillCursor]'s label set.
 *
 * **The mark advances only over a CONTIGUOUS resolved prefix**, which is the one thing this cursor
 * does that no sibling does - see [VoiceNotesBackfill.run]'s `markOpen` handling for the row that
 * makes it necessary (a recording that has not stopped yet is not refused, it is simply not ready,
 * and a high-water mark that jumped past it would strand it for good).
 */
internal object VoiceNotesBackfillCursor {
    private const val PREFS = "voice_notes_backfill_cursor"
    private const val KEY = "voice_notes"
    private const val KEY_UNSYNCABLE = "unsyncable_ids"

    /** ASCII 31, "unit separator" - same choice and same reasoning as [ChecklistsBackfillCursor]'s:
     * the reason is the ENGINE'S own English sentence and may contain any punctuation. */
    private const val FIELD_SEPARATOR = '\u001F'

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastBackfilledId(context: Context): Long = prefs(context).getLong(KEY, 0L)

    fun advance(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY, id).apply()
    }

    /** Rows this install will never send, by local row id, with the reason the engine gave. */
    fun unsyncable(context: Context): Map<Long, String> =
        prefs(context).getStringSet(KEY_UNSYNCABLE, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val at = entry.indexOf(FIELD_SEPARATOR)
                if (at <= 0) null else entry.substring(0, at).toLongOrNull()?.let { it to entry.substring(at + 1) }
            }
            .toMap()

    /** Idempotent: recording the same row twice replaces its reason rather than adding a second
     * entry, since a re-refusal could word itself differently. */
    fun recordUnsyncable(context: Context, id: Long, reason: String) {
        val kept = unsyncable(context).toMutableMap()
        kept[id] = reason
        prefs(context).edit()
            .putStringSet(KEY_UNSYNCABLE, kept.map { (rowId, why) -> "$rowId$FIELD_SEPARATOR$why" }.toSet())
            .apply()
    }

    fun unsyncableCount(context: Context): Int = unsyncable(context).size

    /** Test-only - see [ChecklistsBackfillCursor.resetForTest] for why Robolectric needs it. */
    fun resetForTest(context: Context) {
        prefs(context).edit().clear().apply()
    }
}

/**
 * The one-time upload of every voice note that predates the `voice_notes` transport flip.
 *
 * **`syncToBackend` has exactly two callers and neither of them is "a note that already exists".**
 * [VoiceNoteController.syncToBackend] runs off a SUCCESSFUL transcription and off a rename, so a
 * note transcribed before the flip and never renamed since has no path to the engine at all - and
 * neither does a note whose transcription FAILED, which will never take the first path by
 * definition. [VoiceNotesSync.pull] cannot rescue them either: it only ever matches a server row to
 * a local one by `serverId`, and deliberately never iterates the local rows looking for something
 * to send (its own closing comment says so). Flipping the aspect today would leave every one of
 * them invisible to the engine and to a second client, permanently. This closes that.
 *
 * Same six rules as [ChecklistsBackfill]:
 *
 * 1. **Idempotent.** A pushed row's `serverId` is stamped back onto it immediately, so rule 3 skips
 *    it on every later run. A second run over an unchanged table pushes nothing.
 * 2. **Resumable** via [VoiceNotesBackfillCursor] - a run that dies halfway resumes at the row it
 *    stopped on rather than starting over.
 * 3. **`serverId` is trusted only in the "already present" direction** - a row that has one is
 *    skipped, a row that lacks one is pushed. The reverse would be unsafe, which is why rule 1
 *    exists to make a redundant push harmless.
 * 4. **There is no local tombstone to skip.** `voice_notes` has no `deleted` column: a local delete
 *    is a HARD delete of the row and the `.m4a` beside it ([com.kevin.legion.data.local.VoiceNoteStore.delete],
 *    ADR 0041's "retained or destroyed together"), so a deleted note is simply absent from the scan
 *    and there is nothing for ChecklistsBackfill's rule 4 to be about here.
 * 5. **An UNREACHABLE engine stops the run and is reported** - no cursor advance, so the next
 *    foreground asks again.
 * 6. **A REFUSED row is skipped, recorded, and the run carries on - never a stop.** Ticket 09's
 *    lesson: one legacy checklist tick the engine would not take stopped every other tick from
 *    crossing. The realistic refusal here is `VoiceNoteSerializer.validate`'s
 *    summary-without-a-transcript rule (mirroring the `voice_notes_summary_needs_transcript`
 *    CHECK) - a shape that legacy rows CAN be in, since the phone wrote summary and transcript as
 *    separate columns long before the server had an opinion. The row is left on the phone exactly
 *    as it is, never deleted, with the engine's own sentence recorded beside it.
 *
 * **The audio file is never sent, and cannot be.** This class builds its push through
 * [VoiceNoteController.fieldsOf], the same mapping `rename` and `syncToBackend` use, and
 * [VoiceNoteFields] has no audio field at all - neither does `public.voice_notes`, neither does
 * [RemoteVoiceNote]. Nothing here has to remember to leave [VoiceNote.audioPath] out; there is
 * nowhere to put it. `VoiceNotesBackfillTest` asserts that structurally rather than trusting this
 * paragraph.
 *
 * **A note with no transcript IS pushed**, and that is deliberate. The server allows a null
 * transcript, the row's existence is what has to cross, and the alternative - waiting for a
 * transcript - would strand permanently exactly the notes whose transcription already failed,
 * which is the population this backfill exists for. The other limb's [VoiceNotesSync.pull] then
 * declines to INSERT it (`skippedNoTranscript`), correctly: that phone holds no audio for it, so
 * it would be a recording claimed with no evidence. Nothing is lost - a later successful retry
 * on THIS phone pushes the transcript through the serverId this backfill stamped, and the row then
 * carries its anchor when the other limb next pulls.
 *
 * **A recording still in progress is deferred, not skipped.** See [run].
 */
object VoiceNotesBackfill {

    /** One note the engine will not take, kept so the summary sentence can name it. */
    data class Skipped(val rowId: Long, val reason: String)

    /**
     * @param pushed notes that crossed on THIS run.
     * @param alreadyPresent notes that already carried a `serverId` - rule 3.
     * @param deferredStillRecording notes with no observed stop, left for a later run. **Not a
     *   skip**: nothing is wrong with them and they will be eligible the moment they stop.
     * @param skipped notes held back on THIS run, with the engine's reason. Empty on every run
     *   after the one that met them.
     * @param unsyncableTotal every note this install has ever had refused, this run's included.
     * @param stopped an unreachable/unauthorized engine that ended the run early - rule 5. Null on
     *   a complete pass, and it **never carries a refusal**; that is [skipped]'s job, and
     *   collapsing the two is the defect ticket 09 corrected on checklists.
     */
    data class Report(
        val pushed: Int = 0,
        val alreadyPresent: Int = 0,
        val deferredStillRecording: Int = 0,
        val skipped: List<Skipped> = emptyList(),
        val unsyncableTotal: Int = 0,
        val stopped: String? = null,
    )

    /** What one row decided. [Defer] has no counterpart in [ChecklistsBackfill] - see [run]. */
    private sealed interface RowOutcome {
        data object Pushed : RowOutcome
        data object AlreadyPresent : RowOutcome
        data object Defer : RowOutcome
        data class Skip(val reason: String) : RowOutcome
        data class Stop(val reason: String) : RowOutcome
    }

    private class Tally {
        var pushed = 0
        var alreadyPresent = 0
        var deferred = 0
        val skipped = mutableListOf<Skipped>()
        var stopped: String? = null

        /** The largest id the cursor may be moved to: the end of the CONTIGUOUS run of resolved
         * rows. [RowOutcome.Defer] closes it, so a later row's advance can never step over a
         * deferred one. */
        var mark: Long? = null
        var markOpen = true
    }

    /**
     * Uploads every voice note the engine has never been told about.
     *
     * **A recording that has not stopped yet is DEFERRED, and that is why this cursor is different
     * from every sibling's.** A note with a null `endedAt` and `interrupted = false` is a live
     * recording ([RemoteVoiceNote.endedAtMs]'s own doc comment: "`interrupted` is the column to
     * check for completeness, never this one's nullness"), and pushing it would race
     * [VoiceNoteController.syncToBackend]: both would POST with `serverId = null`, since the local
     * row's `serverId` is null in each one's snapshot, and the engine would end up holding two rows
     * for one recording. So it is left alone - and the high-water mark is left BEHIND it, because a
     * mark that jumped past a deferred row would strand that recording for good the moment it
     * stopped. That is what [Tally.markOpen] is for.
     */
    suspend fun run(context: Context, backend: VoiceNotesBackend): Report {
        val dao = CarDatabase.getDatabase(context).voiceNoteDao()
        val cursorAtStart = VoiceNotesBackfillCursor.lastBackfilledId(context)
        val pending = dao.getAll().filter { it.id > cursorAtStart }.sortedBy { it.id }

        val tally = Tally()
        for (note in pending) {
            if (!record(context, note, examine(context, backend, note), tally)) break
        }
        tally.mark?.let { VoiceNotesBackfillCursor.advance(context, it) }
        return Report(
            pushed = tally.pushed,
            alreadyPresent = tally.alreadyPresent,
            deferredStillRecording = tally.deferred,
            skipped = tally.skipped.toList(),
            // Read AFTER the loop, so it already includes anything this pass recorded.
            unsyncableTotal = VoiceNotesBackfillCursor.unsyncableCount(context),
            stopped = tally.stopped,
        )
    }

    private suspend fun examine(context: Context, backend: VoiceNotesBackend, note: VoiceNote): RowOutcome = when {
        note.serverId != null -> RowOutcome.AlreadyPresent
        note.endedAt == null && !note.interrupted -> RowOutcome.Defer
        else -> push(context, backend, note)
    }

    /**
     * The push, plus the `serverId` write-back that makes rule 1 hold.
     *
     * **[VoiceNoteController.fieldsOf] rather than a mapping of this file's own**, so the three
     * push sites cannot drift about which columns they carry - and so the "audio never leaves the
     * phone" guarantee is a property of one shared shape rather than of three separate memories.
     *
     * The write-back is a narrow `copy(serverId = ...)` through `dao.update`, re-reading the row
     * first: the push is a network call, and a rename or a finished transcription may have landed
     * on the same row while it was in flight. Writing the pre-push snapshot back would undo it.
     */
    private suspend fun push(context: Context, backend: VoiceNotesBackend, note: VoiceNote): RowOutcome {
        val dao = CarDatabase.getDatabase(context).voiceNoteDao()
        val result = backend.upsert(serverId = null, fields = VoiceNoteController.fieldsOf(note))
        val remote = result.getOrNull() ?: return classify(result.exceptionOrNull())
        // One `if` rather than a second early return, to stay under detekt's `ReturnCount`.
        val current = dao.getById(note.id)
        return if (current == null) {
            RowOutcome.Skip("that recording was deleted from this phone while it was being sent")
        } else {
            dao.update(current.copy(serverId = remote.serverId))
            RowOutcome.Pushed
        }
    }

    /** Identical to [ChecklistsBackfill]'s and [PlacesBackfill]'s: a refusal is a fact about the
     * ROW, everything else is a fact about the RUN. [engineRefusalSentence] unwraps DRF's envelope
     * for the sentence only; nothing here rewords the engine. */
    private fun classify(cause: Throwable?): RowOutcome =
        when (val failure = (cause as? EngineHttpException)?.failure) {
            is EngineFailure.Refused -> RowOutcome.Skip(engineRefusalSentence(failure.body))
            null -> RowOutcome.Stop(cause?.message ?: "unknown error")
            else -> RowOutcome.Stop(failure.sentence)
        }

    /**
     * Applies one row's outcome to [tally] and to the cursor mark. Returns false only for
     * [RowOutcome.Stop].
     *
     * **The mark advances on a [RowOutcome.Skip] as well**, which is what makes rule 6 hold: a
     * refused row that happened to be the last pending one would otherwise be retried on every
     * foreground forever - the "error blob on every sync" checklists ended, merely quieter.
     */
    private fun record(context: Context, note: VoiceNote, outcome: RowOutcome, tally: Tally): Boolean {
        when (outcome) {
            is RowOutcome.Pushed -> tally.pushed++
            is RowOutcome.AlreadyPresent -> tally.alreadyPresent++
            is RowOutcome.Defer -> {
                tally.deferred++
                tally.markOpen = false
            }
            is RowOutcome.Skip -> {
                tally.skipped += Skipped(note.id, outcome.reason)
                VoiceNotesBackfillCursor.recordUnsyncable(context, note.id, outcome.reason)
            }
            is RowOutcome.Stop -> {
                tally.stopped = "${outcome.reason} (row id ${note.id})"
                tally.markOpen = false
                return false
            }
        }
        if (tally.markOpen) tally.mark = note.id
        return true
    }

    // --- Foreground auto-trigger ---------------------------------------------------------------

    @Volatile private var lastAutoRunAt = 0L
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** Test seam for the throttle - process-scoped state on an `object`, which Robolectric does not
     * reset between test methods. */
    internal fun setLastAutoRunAtForTest(atMs: Long) {
        lastAutoRunAt = atMs
    }

    /**
     * `MainActivity.onResume`'s hook - runs BEFORE [VoiceNotesSync.maybeAutoPull], the same
     * ordering every sibling backfill uses and for the same reason: an unsent local row must reach
     * the engine before a pull weighs anything against an engine copy that does not know about it.
     *
     * No-ops silently on Supabase and on a device with no engine token - the transport check is the
     * same one [VoiceNotesSync.maybeAutoPull] makes, so an install that has not flipped the
     * `voice_notes` transport row behaves exactly as it did before this file existed.
     */
    suspend fun maybeAutoRun(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        // The three conditions are one expression rather than three early returns, purely to stay
        // under detekt's `ReturnCount` - the same shape, and the same note, as `PlacesSync`'s own
        // `dueBackend`. Reading order unchanged. **The throttle slot is spent only when a real run
        // follows**, so a device with no engine token never burns it.
        val throttled = now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS
        val onDjango =
            EngineTransport(app).transportFor(EngineBackends.ASPECT_VOICE_NOTES) == Transport.DJANGO
        val backend = if (throttled || !onDjango) null else EngineBackends(app).voiceNotesBackend()
        if (backend == null) return
        lastAutoRunAt = now
        guardingForeground(onFailure = { MidnightEvents.voiceNotesBackfillFailed(it) }) {
            val report = run(app, backend)
            MidnightEvents.voiceNotesBackfillSucceeded(
                report.pushed,
                report.alreadyPresent,
                report.deferredStillRecording,
                report.skipped.map { "row ${it.rowId}: ${it.reason}" },
                report.unsyncableTotal,
                report.stopped,
            )
        }
    }
}
