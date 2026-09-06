package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.guardingForeground
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event

/**
 * The one-shot latch for [EventsDoneDivergenceSweep]. A plain boolean, not a high-water cursor like
 * [ChecklistsBackfillCursor] or [ConversationAuditUploadCursor], because this sweep is genuinely
 * once-per-install rather than perpetually-resumable: it exists to repair a bounded set of rows
 * that diverged during one bounded window (the backend cutover to
 * [EventsAppointmentWriter.setDone] landing), and there is no ongoing production of new ones - the
 * push side that was missing is now there.
 *
 * **Latched only on a fully clean pass**, exactly as
 * [com.kevin.legion.data.MidnightImport]'s own `KEY_COMPLETED` is: a run that could not reach the
 * engine, or that failed to push a row, leaves the flag unset so the next foreground retries. That
 * is safe because every push here is an idempotent whole-row PATCH against a server id, so a
 * repeat says the same thing twice rather than doing anything twice.
 */
internal object EventsDoneDivergenceSweepLatch {
    private const val PREFS = "events_done_divergence_sweep"
    private const val KEY_COMPLETED = "completed"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasCompleted(context: Context): Boolean = prefs(context).getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        prefs(context).edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    /** Test-only, and the reason it exists rather than the test reaching into the prefs file: a
     * Robolectric `SharedPreferences` survives between tests in the same class. */
    fun resetForTest(context: Context) {
        prefs(context).edit().remove(KEY_COMPLETED).apply()
    }
}

/**
 * Repairs the rows that diverged while the phone had no push side for a done-toggle.
 *
 * **What went wrong, stated plainly, because this sweep's whole justification is the shape of the
 * damage.** [com.kevin.legion.notes.NotesController.tickAppointment] wrote Room and stopped - no
 * push, no outbox entry (see [EventsAppointmentWriter.setDone]'s own doc comment for the full
 * trace). So every [EventKind.TASK] ticked on this phone between the backend cutover and
 * 2026-09-06 reads done on the phone and `done = false` on the server, with nothing anywhere that
 * would ever have reconciled them: the pull's rule 6 deliberately never visits a local row, and
 * the drain has nothing queued to drain.
 *
 * **The direction is local-over-server, and that is a ruling, not a default.** A tick the user made
 * is a real act the server simply never heard about; overwriting the phone from the server would
 * delete it. Kevin, in this sweep's own brief: *"Do NOT silently overwrite the phone from the
 * server - a tick the user made is real and the server simply never heard it."*
 *
 * **Three narrowings, each one closing a way this could destroy something instead of repairing
 * it:**
 *
 * 1. **[EventKind.TASK] only.** An [EventKind.EVENT] can never be ticked at all (one-today ticket
 *    08 - "i dont mark an event done, it just passes"), and a reminder's tick goes through
 *    [com.kevin.legion.notes.NotesController.applyChange], which is server-FIRST: its local `done`
 *    is written only on a genuine ACK, so a reminder cannot have diverged this way. Task is
 *    therefore not a conservative subset, it is exactly the affected set.
 * 2. **Strictly local-newer only** (`local.updatedAtMs > remote.updatedAtMs`). This is what makes
 *    the sweep safe to run beside a live PWA. If the OTHER client ticked a row, the server copy is
 *    the newer one and this leaves it completely alone - pushing the older local value over it
 *    would be the "silently overwrite" this exists to avoid, pointed the other way. A row that is
 *    still diverging AND locally newer is diverging precisely because a local write never left the
 *    device, which is the defect. This is [EventsSync.pull]'s own rule 4/rule 6 tiebreak, applied
 *    in the one direction the pull structurally cannot: rule 6 never visits a local row at all.
 * 3. **A row the server did not return at all is skipped**, never created. This sweep repairs a
 *    `done` flag on rows both sides already hold; a local row the server has never seen is
 *    [EventsAppointmentWriter]'s business, not this one, and inventing a create here would double
 *    up with the outbox.
 *
 * **Ordering against [EventsSync.pull] is deliberately NOT load-bearing, and that is worth saying
 * because every backfill in this package's ordering IS.** A backfill must run before its pull, or
 * last-write-wins gets weighed against a server copy that does not know about the unsent row. This
 * sweep needs no such guarantee: narrowing 2 compares the two timestamps directly, so an unpulled
 * server row is skipped for being newer rather than mistaken for a divergence. That independence
 * is not a nicety - `MainActivity` calls this after [EventsSync.maybeAutoPull], which is
 * fire-and-forget on its own scope AND five-minute throttled, so "after the pull" is not something
 * the call site could promise even if this needed it.
 */
object EventsDoneDivergenceSweep {

    /**
     * @param examined local [EventKind.TASK] rows with a server id that this run compared.
     * @param pushed rows whose local `done` was sent up because it was newer and differed.
     * @param skippedServerNewer rows that differ but where the SERVER copy is newer or equal -
     *   left entirely alone (narrowing 2). Counted rather than silently dropped so a nonzero value
     *   is visible in the breadcrumb if this ever runs before a pull by mistake.
     * @param skippedNotOnServer local rows with a server id the engine did not return (narrowing
     *   3) - a tombstoned row, or one outside what [EventsBackend.fetchActive] hands back.
     * @param failed one sentence per row that could not be pushed. **A non-empty list means the
     *   latch is NOT set** and the whole sweep runs again next foreground.
     */
    data class Report(
        val examined: Int,
        val pushed: Int,
        val skippedServerNewer: Int,
        val skippedNotOnServer: Int,
        val failed: List<String>,
    )

    /**
     * One pass. **Never latches the flag itself** - [maybeAutoRun] does that, and only on a clean
     * pass, so a caller that wants to run this repeatedly (a test, a debug button) is not fighting
     * a latch it did not set.
     *
     * A failure to READ the server aborts before anything is written: with no server state there is
     * nothing to compare against, and treating "could not ask" as "nothing differs" is the
     * unreadable-vs-empty conflation CLAUDE.md section 1 names by name.
     */
    suspend fun run(context: Context, backend: EventsBackend): Report {
        val remote = backend.fetchActive().getOrElse { e ->
            return Report(
                examined = 0,
                pushed = 0,
                skippedServerNewer = 0,
                skippedNotOnServer = 0,
                failed = listOf("could not read the server's own copy: ${e.message ?: "unknown error"}"),
            )
        }
        val remoteById = remote.associateBy { it.serverId }

        val db = CarDatabase.getDatabase(context)
        // Paired with the server id up front rather than filtered on `serverId != null` and
        // re-read inside the loop - it is the same narrowing, expressed so the loop body never
        // has a nullable to unwrap and therefore never needs a jump to bail out of.
        val localTasks = db.eventDao().getAll()
            .filter { !it.deleted && it.kind == EventKind.TASK }
            .mapNotNull { row -> row.serverId?.let { it to row } }

        var examined = 0
        var pushed = 0
        var skippedServerNewer = 0
        var skippedNotOnServer = 0
        val failed = mutableListOf<String>()

        for ((serverId, local) in localTasks) {
            when (verdictFor(local, remoteById[serverId])) {
                Verdict.NOT_ON_SERVER -> skippedNotOnServer++
                Verdict.AGREES -> examined++
                Verdict.SERVER_NEWER -> {
                    examined++
                    skippedServerNewer++
                }
                Verdict.PUSH_LOCAL -> {
                    examined++
                    val result = backend.upsert(serverId, local.toEventFields())
                    if (result.isSuccess) pushed++ else failed += describeFailure(local, result.exceptionOrNull())
                }
            }
        }

        return Report(examined, pushed, skippedServerNewer, skippedNotOnServer, failed)
    }

    /** The four things one row can be, and the ONLY place the three narrowings in this object's
     * class doc are actually decided. Pulled out of the loop so each narrowing is one readable
     * line rather than a `continue` somebody could reorder without noticing. */
    private enum class Verdict { NOT_ON_SERVER, AGREES, SERVER_NEWER, PUSH_LOCAL }

    private fun verdictFor(local: Event, server: RemoteEvent?): Verdict = when {
        server == null -> Verdict.NOT_ON_SERVER
        server.done == local.done -> Verdict.AGREES
        // Strictly newer, never newer-or-equal: a tie belongs to EventsSync.pull's rule 4, which
        // resolves it toward the server. Resolving it the other way here would make the two
        // mechanisms disagree about the same row on alternate foregrounds.
        local.updatedAtMs <= server.updatedAtMs -> Verdict.SERVER_NEWER
        else -> Verdict.PUSH_LOCAL
    }

    /** Names the row as the user would recognise it, not only by id - this sentence can reach a
     * breadcrumb Kevin reads next to a task he remembers ticking. */
    private fun describeFailure(local: Event, cause: Throwable?) =
        "\"${local.title}\" (row ${local.id}): ${cause?.message ?: "unknown error"}"

    /**
     * `MainActivity.onResume`'s hook. Silent, cheap no-op once the latch is set, or when `events`
     * is not on a usable transport / nobody is signed in - the same posture every other automatic
     * pass in this package takes.
     *
     * **The latch is read BEFORE the backend is resolved**, so a completed install does no work at
     * all rather than resolving a backend and then discarding it.
     */
    suspend fun maybeAutoRun(context: Context) {
        val app = context.applicationContext
        if (EventsDoneDivergenceSweepLatch.hasCompleted(app)) return
        val backend = EngineBackends(app).eventsBackendAfterAuth() ?: return
        guardingForeground(onFailure = { MidnightEvents.eventsDoneDivergenceSweepFailed(it) }) {
            val report = run(app, backend)
            if (report.failed.isEmpty()) EventsDoneDivergenceSweepLatch.markCompleted(app)
            MidnightEvents.eventsDoneDivergenceSwept(
                report.examined,
                report.pushed,
                report.skippedServerNewer,
                report.skippedNotOnServer,
                report.failed,
            )
        }
    }
}
