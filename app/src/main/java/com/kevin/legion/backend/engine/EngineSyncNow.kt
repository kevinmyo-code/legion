package com.kevin.legion.backend.engine

import android.content.Context
import com.kevin.legion.backend.ChecklistsBackfill
import com.kevin.legion.backend.ChecklistsOutboxDrain
import com.kevin.legion.backend.ChecklistsSync
import com.kevin.legion.backend.EventsOutboxDrain
import com.kevin.legion.backend.EventsSync
import com.kevin.legion.backend.PlacesBackfill
import com.kevin.legion.backend.PlacesSync
import com.kevin.legion.backend.VoiceNotesBackfill
import com.kevin.legion.backend.VoiceNotesSync

/**
 * The debug Setup screen's "SYNC NOW" row: runs the engine's drain-then-backfill-then-pull for
 * whichever aspects are on [Transport.DJANGO], right now, ignoring every five-minute throttle, and
 * hands back ONE sentence saying what actually happened.
 *
 * **The sentence is the point.** Every automatic path in this file's neighbourhood reports only to
 * logcat (`MidnightEvents`), which is fine for a background pass and useless when Kevin is stood in
 * front of the phone trying to find out whether the laptop engine is reachable at all. This is the
 * one surface that answers that in words - and it answers it honestly: rows pulled, rows still
 * queued, or the engine's own failure sentence verbatim. **Nothing here says "synced" off anything
 * but a real result** (CLAUDE.md section 7); an aspect that is not on Django, or a device with no
 * token, is reported as exactly that rather than as a quiet success.
 *
 * **No `object` singleton** - a class taking [Context] and its [EngineBackends] as constructor
 * parameters, which is also how a test substitutes fakes.
 */
class EngineSyncNow(
    context: Context,
    private val backends: EngineBackends = EngineBackends(context),
) {
    private val app: Context = context.applicationContext

    suspend fun run(): String {
        val lines = mutableListOf<String>()
        lines += eventsLine()
        lines += checklistsLine()
        lines += placesLine()
        lines += voiceNotesLine()
        return lines.joinToString("\n")
    }

    private suspend fun eventsLine(): String {
        val note = fallbackNote(EngineBackends.ASPECT_EVENTS)
        val backend = backends.eventsBackendAfterAuth()
            ?: return "Events: not on the engine (transport is Supabase, or no token on this device).$note"
        var failed: String? = null
        val line = guardingForeground(onFailure = { failed = it.message ?: "failed, with no message." }) {
            // Drain first, then pull - the same load-bearing ordering EventsOutboxDrain's own
            // class doc argues for, reproduced here rather than skipped because this button is
            // "do the foreground pass now", not a second, differently-ordered mechanism.
            val drained = EventsOutboxDrain.drain(app, backend)
            val pulled = EventsSync.pull(app, backend)
            "Events: pulled ${pulled.inserted} new, ${pulled.updated} updated, " +
                "${pulled.tombstoned} removed; sent ${drained.succeeded}, " +
                "${drained.stillPending} still queued, ${drained.poisoned} stuck."
        }
        return (line ?: "Events: $failed") + note
    }

    private suspend fun checklistsLine(): String {
        val note = fallbackNote(EngineBackends.ASPECT_CHECKLISTS)
        val backend = backends.checklistsBackend()
            ?: return "Checklists: not on the engine (no engine sign-in on this device, or the " +
                "checklists transport row is set to Supabase).$note"
        var failed: String? = null
        val line = guardingForeground(onFailure = { failed = it.message ?: "failed, with no message." }) {
            val drained = ChecklistsOutboxDrain.drain(app, backend)
            val backfilled = ChecklistsBackfill.run(app, backend)
            val pulled = ChecklistsSync.pull(app, backend)
            // The backfill reports a per-table stop rather than throwing (see its own rule 5), so
            // a partial run has to be said in words here or it would read as a clean pass.
            val stoppedNote = if (backfilled.stopped.isEmpty()) {
                ""
            } else {
                " Backfill stopped early, and will resume next sync: " + backfilled.stopped.joinToString("; ")
            }
            "Checklists: pulled ${pulled.inserted} new, ${pulled.updated} updated, " +
                "${pulled.tombstoned} removed; ${backfillPhrase(backfilled)}; " +
                "sent ${drained.succeeded}, ${drained.stillPending} still queued, " +
                "${drained.poisoned} stuck.$stoppedNote"
        }
        return (line ?: "Checklists: $failed") + note
    }

    /**
     * Places: backfill, then pull, in that order and for the reason `MainActivity.onResume` states -
     * an unsent local row must reach the engine before a pull weighs an engine copy that does not
     * know about it.
     *
     * **`as? DjangoPlacesBackend` is the transport check**, not a cast for convenience: only
     * `DjangoPlacesBackend` implements the tombstone-carrying `?since=` feed both halves need, so
     * on Supabase this resolves to null and the line says exactly that rather than half-running.
     */
    private suspend fun placesLine(): String {
        val note = fallbackNote(EngineBackends.ASPECT_PLACES)
        val backend = backends.placesBackend() as? DjangoPlacesBackend
            ?: return "Places: not on the engine (transport is Supabase, or no token on this device).$note"
        var failed: String? = null
        val line = guardingForeground(onFailure = { failed = it.message ?: "failed, with no message." }) {
            val backfilled = PlacesBackfill.run(app, backend)
            val pulled = PlacesSync.pull(app, backend)
            "Places: pulled ${pulled.inserted} new, ${pulled.updated} updated, " +
                "${pulled.tombstoned} removed; ${placesBackfillPhrase(backfilled)}." +
                stoppedNote(backfilled.stopped)
        }
        return (line ?: "Places: $failed") + note
    }

    /** Voice notes: backfill, then pull. Same ordering and the same concrete-class `as?`
     * transport check as [placesLine], and for the same reason - only `DjangoVoiceNotesBackend`
     * implements both the write side and the tombstone-carrying `?since=` feed.
     *
     * **Nothing on this path touches the `.m4a`** - see
     * [com.kevin.legion.backend.VoiceNotesBackfill]'s own class doc. */
    private suspend fun voiceNotesLine(): String {
        val note = fallbackNote(EngineBackends.ASPECT_VOICE_NOTES)
        val backend = backends.voiceNotesBackend() as? DjangoVoiceNotesBackend
            ?: return "Recordings: not on the engine (transport is Supabase, or no token on this device).$note"
        var failed: String? = null
        val line = guardingForeground(onFailure = { failed = it.message ?: "failed, with no message." }) {
            val backfilled = VoiceNotesBackfill.run(app, backend)
            val pulled = VoiceNotesSync.pull(app, backend)
            "Recordings: pulled ${pulled.inserted} new, ${pulled.updated} updated, " +
                "${pulled.tombstoned} removed; ${voiceNotesBackfillPhrase(backfilled)}." +
                stoppedNote(backfilled.stopped)
        }
        return (line ?: "Recordings: $failed") + note
    }

    /** The one sentence a run that ended early owes, shared by both new lines. A backfill reports a
     * stop rather than throwing (each one's rule 5), so a partial run has to be said in words here
     * or it reads as a clean pass - the same clause `checklistsLine` builds inline. */
    internal fun stoppedNote(stopped: String?): String =
        if (stopped == null) "" else " Backfill stopped early, and will resume next sync: $stopped"

    /**
     * Places' backfill clause, in words: how many crossed, how many never will, and why. Same three
     * branches, and the same argument for them, as [backfillPhrase] - see its doc comment for why
     * a bare "backfilled 0" over a permanently held-back row is the thing to avoid.
     *
     * `internal` for the same reason [backfillPhrase] is: the wording IS the deliverable and
     * `EngineSyncNowPhraseTest` reaches it directly, because a test that had to go through [run]
     * would need a live engine and would not be testing the wording at all.
     */
    internal fun placesBackfillPhrase(report: PlacesBackfill.Report): String = when {
        report.skipped.isNotEmpty() ->
            "sent ${report.pushed}, held back ${report.skipped.size} " +
                "(kept on this phone, never sent: " +
                report.skipped.joinToString("; ") { it.reason.trimEnd('.') } + ")"
        report.unsyncableTotal > 0 ->
            "sent ${report.pushed} (${report.unsyncableTotal} the engine will not take, " +
                "kept on this phone and never sent)"
        else -> "sent ${report.pushed}"
    }

    /** Voice notes' backfill clause. One branch more than [placesBackfillPhrase]: a live recording
     * is DEFERRED, which is neither a success nor a refusal, and saying "sent 0" over one would
     * read as a failure when nothing is wrong at all. */
    internal fun voiceNotesBackfillPhrase(report: VoiceNotesBackfill.Report): String {
        val deferred = if (report.deferredStillRecording > 0) {
            ", ${report.deferredStillRecording} still recording (will go next time)"
        } else {
            ""
        }
        return when {
            report.skipped.isNotEmpty() ->
                "sent ${report.pushed}$deferred, held back ${report.skipped.size} " +
                    "(kept on this phone, never sent: " +
                    report.skipped.joinToString("; ") { it.reason.trimEnd('.') } + ")"
            report.unsyncableTotal > 0 ->
                "sent ${report.pushed}$deferred (${report.unsyncableTotal} the engine will not " +
                    "take, kept on this phone and never sent)"
            else -> "sent ${report.pushed}$deferred"
        }
    }

    /**
     * The clause that says a Django default did not apply, appended to whichever sentence this
     * aspect produced. **Empty for everything else**, so a line only grows when there is something
     * to report.
     *
     * `events` and `checklists` default to [Transport.DJANGO] since 2026-09-06, but only on a
     * device that holds an engine address and a token; without one they resolve to Supabase
     * instead of to no backend at all (see [EngineTransport]'s class doc for why that guard exists).
     * A fallback nobody is told about looks exactly like a setting that never took effect, which is
     * the failure this button exists to prevent - so it is said in words, on the line for the
     * aspect it happened to, whether that line otherwise reports a success or a failure.
     */
    // `internal`, not `private`, for the same reason as `backfillPhrase` below: the wording IS the
    // deliverable, and `EngineSyncNowPhraseTest` can reach it with a real EngineBackends built over
    // a signed-out EngineConfig, where a test that had to go through `run()` would need a live
    // engine and would not be testing the wording at all.
    internal fun fallbackNote(aspect: String): String =
        if (backends.isFallingBackToSupabase(aspect)) {
            " Django is this aspect's default now, but this device is not signed in to an engine, " +
                "so it is on Supabase."
        } else {
            ""
        }

    /**
     * The backfill's own clause, in words: how many crossed, how many never will, and why.
     *
     * **This replaces a line that printed the engine's raw JSON refusal on every sync, forever.**
     * Before 2026-09-06 the backfill stopped on its first refusal, so the Setup screen read
     * `Backfill stopped: checklist_ticks: {"non_field_errors":["\"3 sets goblet squats\" is
     * measured in kg - give a number to tick it, nothing was recorded."]} (row id 1, syncId
     * 0f8195ff-...)` - an error blob, repeated identically after every sync, while zero ticks
     * crossed. The refusal itself was correct; what was missing was a sentence.
     *
     * **The kept-on-this-phone clause is a ruling, not a nicety** - the tick records something the
     * user really did, before the item was ever given a unit, so it stays on the device and is
     * simply never sent (see [ChecklistsBackfill]'s rule 6). Saying that out loud is what stops
     * "skipped" reading as "discarded".
     *
     * **The engine's sentence loses only its full stop**, because it sits inside a parenthetical
     * that is itself mid-sentence and `recorded.); sent 0` reads as a typo. That is the same class
     * of change as [engineRefusalSentence]'s unwrapping - punctuation, not wording - and the
     * verbatim body still reaches the user untouched on the path built for it
     * (`ChecklistsWriteThrough.PushOutcome.Refused`, which hands back `EngineFailure.Refused.body`
     * itself).
     */
    // `internal`, not `private`, purely so `EngineSyncNowPhraseTest` can assert the exact
    // sentence. There is no seam to fake this class's EngineBackends (it is a final class), and
    // the wording IS the deliverable here - a test that could only reach it through a live engine
    // would not be a test of the wording at all.
    internal fun backfillPhrase(report: ChecklistsBackfill.Report): String = when {
        report.skipped.isNotEmpty() ->
            "backfilled ${report.pushed}, skipped ${report.skipped.size} " +
                "(kept on this phone, never sent: " +
                report.skipped.joinToString("; ") { it.reason.trimEnd('.') } + ")"
        // Nothing NEW was skipped this run, but something is still being held back, so a bare
        // "backfilled 0" would read as a clean sweep of everything there is.
        report.unsyncableTotal > 0 ->
            "backfilled ${report.pushed} (${report.unsyncableTotal} the engine will not take, " +
                "kept on this phone and never sent)"
        else -> "backfilled ${report.pushed}"
    }
}
