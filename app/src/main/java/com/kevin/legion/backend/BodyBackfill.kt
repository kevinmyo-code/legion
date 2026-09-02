package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.BodyweightLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MealLog
import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.data.local.SleepLog
import com.kevin.legion.data.local.SleepTarget
import com.kevin.legion.data.local.WorkoutPlan
import com.kevin.legion.data.local.WorkoutPlanItem
import com.kevin.legion.data.local.WorkoutSetLog

/**
 * Per-table install-scoped high-water mark for [BodyBackfill]'s one-time (then perpetually
 * cheap-no-op) upload - same shape as [ObdSampleUploadCursor], one watermark PER table like
 * [BodyPullCursor] rather than [ObdSampleUploadCursor]'s single one, because the eight body tables
 * have no ordering dependency on each other the way a single growing `obd_samples` table does.
 *
 * **The watermark is a local row id, not a timestamp** - unlike [BodyPullCursor] (which tracks "how
 * far into the SERVER's `updated_at` history have I merged"), this tracks "how far into the LOCAL
 * table, in insertion order, has this device already decided every row's fate" - pushed, already
 * present, or knowingly skipped. A row's local id never changes and is assigned once at insert, so
 * it is a safe, monotonic ordering key for "have I looked at this one yet" even though it says
 * nothing about server state directly.
 */
internal object BodyBackfillCursor {
    private const val PREFS = "body_backfill_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last local id this device's backfill has advanced past for [table], or 0 (before the
     * table's first row) if backfill has never run against this table. */
    fun lastBackfilledId(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    /** Persisted after every row this run decides the fate of, not just once at the end of
     * [BodyBackfill.run] - see [ObdSampleUploadCursor.advance]'s own doc for why: a run interrupted
     * mid-way resumes from its last real progress rather than re-deciding rows already settled. */
    fun advance(context: Context, table: String, id: Long) {
        prefs(context).edit().putLong(table, id).apply()
    }
}

/**
 * The one-time backfill for body: every local row that predates write-through
 * (`BodyWriteThrough`/`BodyOutbox.kt`, `c408b4f`) has no path to the server at all, because
 * write-through only ever pushes NEW writes going forward. Measured on the real phone 2026-09-02:
 * all eight server tables at zero rows while the phone holds 42. **This is the template for the
 * same gap in the six aspects after this one** (map ticket 05), so the shape here - not just the
 * code - is what is worth copying:
 *
 * 1. **Idempotent via the SAME natural key every other body write already uses.** Every push here
 *    is a plain [BodyBackend.upsertX] call keyed on `origin_guid`, exactly like
 *    [BodyWriteThrough]'s own create path (verified genuinely idempotent by reading
 *    [SupabaseBodyBackend]'s `onConflict = "origin_guid"` on every one of the eight upserts, not
 *    assumed - the brief for this exact ticket flagged `EventsBackend.upsert` as a prior example
 *    of an upsert that turned out NOT to be idempotent, which is exactly why this was read rather
 *    than taken on faith; see [BodyBackend]'s own class doc for the contrast with that function).
 *    Running this object twice, or interrupting it and re-running it, can at worst re-send a row
 *    Postgres already has; it can never duplicate one.
 * 2. **Resumable via [BodyBackfillCursor]**, one high-water mark per table, same reasoning as
 *    [ObdSampleUploadCursor]'s own doc: idempotency makes a re-scan CORRECT, the cursor is what
 *    makes a routine re-run CHEAP (touches nothing once every row has been decided).
 * 3. **A local row's own [serverId] is NOT trusted as "was this ever pushed"** - deliberately, and
 *    this is the one place this design earns its keep over the obvious-looking alternative.
 *    [BodyWriteThrough]'s create functions never write [serverId] back onto the local row on a
 *    successful push (only the next [BodySync.pull] does, by finding the row again via its guid),
 *    so a row created THIS MORNING via write-through and a row created two months before
 *    write-through existed look IDENTICAL by that one field: both have `serverId == null`. A row
 *    with `serverId != null`, however, has definitely round-tripped a pull and definitely exists
 *    server-side - that direction of the check is sound, so it is the only direction used to skip a
 *    push (`alreadyPresent`). A row with `serverId == null` is always pushed regardless of whether
 *    it MIGHT already be server-side by write-through's own doing - rule 1's idempotency is exactly
 *    what makes that redundant push harmless rather than a duplicate. **This is the same shape
 *    [ObdSampleReconcile]'s own class doc chose deliberately** ("does not attempt a symmetric diff
 *    at all... the natural key means 'is this row on the server' is never actually in doubt"),
 *    applied here for a different reason (a write-through gap in [serverId], not sheer table size).
 * 4. **A local row that is BOTH unsynced (`serverId == null`) AND already soft-deleted locally is
 *    skipped, never pushed as a bare upsert and never pushed via [BodyBackend.softDeleteX]
 *    either.** A plain upsert would resurrect it server-side as an ACTIVE row (upsert never sets
 *    `deleted`); [BodyBackend.softDeleteX] only ever `UPDATE`s a row matched by `origin_guid` and
 *    is a silent no-op (`Result.success(false)`, never a failure) when no such row exists yet - so
 *    calling it alone would report "handled" while creating nothing at all. Kevin's own framing of
 *    this ticket answers what to do here: a wipe-and-rebuild only DESTROYS a row that never
 *    uploaded and was otherwise going to survive - a row already deleted locally has nothing left
 *    to destroy, so it is counted honestly as [Report.skippedLocalOnlyDeleted] and left alone
 *    rather than manufactured a server history it never had.
 * 5. **Reports honestly, and per-table failures do not abort the whole run.** Unlike
 *    [ObdSampleReconcile]'s single table (where one halt is the whole story), the eight body
 *    tables have no ordering dependency on each other, so a transient failure partway through one
 *    table's rows halts only THAT table's cursor (leaving the failing row to retry next run,
 *    same "halt, don't skip-and-continue past an unresolved row" posture as
 *    [ObdSampleReconcile]) while every other table's backfill still runs to completion this pass.
 * 6. **Cheap steady state without a dedicated `getAfterId` query.** Each table's backfill reads
 *    `dao.getAll()` - the same whole-table read [BodySync.pull] already performs every five minutes
 *    on these same eight tables - and filters to `id > cursor` in memory. At the row counts this
 *    aspect and the next six actually carry (dozens to a few hundred - see the map's own
 *    per-aspect row counts), a `getAll()` is a sub-millisecond Room query; the cursor's saving is in
 *    not re-PUSHING or re-DECIDING rows already settled, not in avoiding the read itself. A future
 *    aspect large enough for that read itself to matter should follow [ObdSampleReconcile]'s
 *    `getAfterId(cursor, BATCH_SIZE)` shape instead - flagged here rather than pre-built, since
 *    building it now for a table three orders of magnitude smaller than `obd_samples` would be
 *    speculative.
 */
object BodyBackfill {
    /** One table's own backfill outcome - never surfaced on its own, only folded into [Report]. */
    private data class TableResult(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        /** Non-null only when this table's loop halted on a push failure - see this file's class
         * doc point 5. Names the table and the row so a human reading the log knows exactly where
         * to look, never just "something failed somewhere". */
        val failure: String?,
    )

    /**
     * @param pushed rows this run genuinely sent (an upsert Postgres accepted) across all eight
     *   tables - counted by call, matching [ObdSampleReconcile.Report.uploaded]'s own "a repost
     *   still counts" convention.
     * @param alreadyPresent rows this run skipped because their local [serverId] already proved
     *   they exist server-side - see this file's class doc point 3 for why this is the only
     *   direction that check is trusted.
     * @param skippedLocalOnlyDeleted rows this run skipped because they are unsynced AND already
     *   soft-deleted locally - see this file's class doc point 4 for why pushing them would be
     *   wrong rather than merely redundant.
     * @param failed one entry per table whose backfill halted on a push failure this run
     *   (`"<table>: <message> (row id <id>)"`), empty on the healthy/steady-state run. A silent
     *   success on zero rows pushed is exactly the failure mode this whole ticket exists to catch,
     *   so [failed] is reported in words rather than folded into a single boolean.
     */
    data class Report(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        val failed: List<String>,
    )

    private suspend fun <L> backfillTable(
        context: Context,
        table: String,
        rows: List<L>,
        localId: (L) -> Long,
        localGuid: (L) -> String,
        localServerId: (L) -> String?,
        localDeleted: (L) -> Boolean,
        push: suspend (L) -> Result<*>,
    ): TableResult {
        val cursorAtStart = BodyBackfillCursor.lastBackfilledId(context, table)
        val pending = rows.filter { localId(it) > cursorAtStart }.sortedBy { localId(it) }

        var pushed = 0
        var alreadyPresent = 0
        var skippedLocalOnlyDeleted = 0
        var failure: String? = null

        for (row in pending) {
            when {
                localServerId(row) != null -> {
                    alreadyPresent++
                    BodyBackfillCursor.advance(context, table, localId(row))
                }
                localDeleted(row) -> {
                    skippedLocalOnlyDeleted++
                    BodyBackfillCursor.advance(context, table, localId(row))
                }
                else -> {
                    val result = push(row)
                    if (result.isSuccess) {
                        pushed++
                        BodyBackfillCursor.advance(context, table, localId(row))
                    } else {
                        val message = result.exceptionOrNull()?.message ?: "unknown error"
                        failure = "$table: $message (row id ${localId(row)}, guid ${localGuid(row)})"
                        // Halt THIS table's loop - see this file's own class doc point 5. The
                        // cursor was already advanced past every row settled before this one, so
                        // the next run resumes at exactly this row rather than re-deciding earlier
                        // ones or skipping this one silently.
                        break
                    }
                }
            }
        }

        return TableResult(pushed, alreadyPresent, skippedLocalOnlyDeleted, failure)
    }

    /** Every function below reuses [BodyWriteThrough]'s own payload-mapping classes
     * ([BodyWriteThrough.BodyweightLogPayload] etc.) rather than re-deriving `Local -> Fields`,
     * so a field this codebase's own standing lesson about two implementations of one mapping
     * drifting apart never applies here - there is only ever one mapping, [BodyWriteThrough]'s. */
    suspend fun run(context: Context, backend: BodyBackend): Report {
        val db = CarDatabase.getDatabase(context)

        val bodyweight = backfillTable(
            context, OutboxTarget.BODY_BODYWEIGHT_LOGS, db.bodyweightLogDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: BodyweightLog -> backend.upsertBodyweightLog(row.guid, BodyWriteThrough.BodyweightLogPayload.from(row).toFields()) },
        )
        val mealLogs = backfillTable(
            context, OutboxTarget.BODY_MEAL_LOGS, db.mealLogDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: MealLog -> backend.upsertMealLog(row.guid, BodyWriteThrough.MealLogPayload.from(row).toFields()) },
        )
        val mealTargets = backfillTable(
            context, OutboxTarget.BODY_MEAL_TARGETS, db.mealTargetDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: MealTarget -> backend.upsertMealTarget(row.guid, BodyWriteThrough.MealTargetPayload.from(row).toFields()) },
        )
        val sleepLogs = backfillTable(
            context, OutboxTarget.BODY_SLEEP_LOGS, db.sleepLogDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: SleepLog -> backend.upsertSleepLog(row.guid, BodyWriteThrough.SleepLogPayload.from(row).toFields()) },
        )
        val sleepTargets = backfillTable(
            context, OutboxTarget.BODY_SLEEP_TARGETS, db.sleepTargetDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: SleepTarget -> backend.upsertSleepTarget(row.guid, BodyWriteThrough.SleepTargetPayload.from(row).toFields()) },
        )
        val workoutPlans = backfillTable(
            context, OutboxTarget.BODY_WORKOUT_PLANS, db.workoutPlanDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: WorkoutPlan -> backend.upsertWorkoutPlan(row.guid, BodyWriteThrough.WorkoutPlanPayload.from(row).toFields()) },
        )
        val workoutPlanItems = backfillTable(
            context, OutboxTarget.BODY_WORKOUT_PLAN_ITEMS, db.workoutPlanItemDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: WorkoutPlanItem -> backend.upsertWorkoutPlanItem(row.guid, BodyWriteThrough.WorkoutPlanItemPayload.from(row).toFields()) },
        )
        val workoutSetLogs = backfillTable(
            context, OutboxTarget.BODY_WORKOUT_SET_LOGS, db.workoutSetLogDao().getAll(),
            localId = { it.id }, localGuid = { it.guid }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: WorkoutSetLog -> backend.upsertWorkoutSetLog(row.guid, BodyWriteThrough.WorkoutSetLogPayload.from(row).toFields()) },
        )

        val results = listOf(
            bodyweight, mealLogs, mealTargets, sleepLogs, sleepTargets,
            workoutPlans, workoutPlanItems, workoutSetLogs,
        )

        return Report(
            pushed = results.sumOf { it.pushed },
            alreadyPresent = results.sumOf { it.alreadyPresent },
            skippedLocalOnlyDeleted = results.sumOf { it.skippedLocalOnlyDeleted },
            failed = results.mapNotNull { it.failure },
        )
    }

    // --- Foreground auto-trigger ------------------------------------------------------------------

    @Volatile private var lastAutoRunAt = 0L

    /** Deliberately the same floor as [BodySync]'s own auto-pull interval - once every row has
     * been decided (the expected steady state within minutes of this shipping), this call costs
     * eight `getAll()` reads and nothing else, so there is no need for a longer floor than the
     * pull it runs alongside. */
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * `MainActivity.onResume`'s hook - runs on the same foreground path as the drain and the pull,
     * and BEFORE the pull for the identical reason [BodyOutboxDrain]'s own class doc gives for
     * draining before pulling: an unsent local row must reach the server before the pull weighs
     * last-write-wins against a server copy that does not know about it yet. Placed after the
     * outbox drain (draining a WRITE-THROUGH failure) and before [BodySync.maybeAutoPull] - the
     * outbox only ever holds rows write-through already tried and failed to push, which is a
     * different set of rows from backfill's "never even attempted" set, so there is no ordering
     * requirement between the drain and this call, only between this call (and the drain) and the
     * pull that follows both.
     *
     * **`suspend`, run INLINE - deliberately NOT fire-and-forget on its own [CoroutineScope] the
     * way [BodySync.maybeAutoPull]/[LedgerReconcile.maybeAutoRun] are.** Those two are the last
     * call on their respective foreground paths, so nothing downstream needs to wait for them.
     * This one is not: `ui/MainActivity.kt`'s `onResume` hook calls this and
     * [BodySync.maybeAutoPull] back to back inside the SAME `lifecycleScope.launch` block, and if
     * this function returned after merely SCHEDULING its network calls (the fire-and-forget
     * shape), the very next line would schedule the pull with no guarantee the backfill's pushes
     * had reached the server yet - silently reintroducing the exact ordering bug this ticket
     * exists to prevent. Suspending here, inline, means the pull is not even scheduled until every
     * push this run attempts has already completed (succeeded or halted), same discipline
     * [BodyOutboxDrain.maybeDrain]'s own class doc states for the drain immediately before it.
     * No-ops silently when Supabase is not configured or nobody is signed in, matching
     * [BodyOutboxDrain.maybeDrain]'s own guard shape.
     *
     * **Cold-start fix, 2026-09-02.** The guard here used to be a raw `currentUserId() == null`
     * read - the same race [EventsSync.maybeAutoPull]'s own doc comment traces, just never carried
     * over to this file. It now awaits [SupabaseAuth.resolveSignedInUserId] instead, which is
     * `suspend` and can genuinely wait out the restore since this whole function already runs
     * inline (see this doc comment's own paragraph on why that matters). The throttle slot is
     * reserved BEFORE this await, same reservation-before-await shape
     * [EventsSync.maybeAutoPull]'s doc comment states the reasoning for - otherwise a second
     * `onResume` firing while this one is still awaiting the restore could schedule a second run.
     */
    suspend fun maybeAutoRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoRunAt = now
        try {
            val report = runIfSignedIn(app, SupabaseAuth(app), SupabaseBodyBackend(client)) ?: return
            MidnightEvents.bodyBackfillSucceeded(report.pushed, report.alreadyPresent, report.skippedLocalOnlyDeleted, report.failed)
        } catch (e: Exception) {
            MidnightEvents.bodyBackfillFailed(e)
        }
    }

    /**
     * [maybeAutoRun]'s guard-then-[run], factored out so `BodyBackfillTest` can drive the
     * "still restoring, then succeeds" retry directly against a fake [SupabaseAuthGateway] (via
     * [auth]'s own test seam) and a fake [BodyBackend] - without a real
     * [SupabaseClientProvider]-backed client, which nothing in this test environment has (same
     * posture [EventsSync.resolveUserIdForAutoPull]'s own doc comment states for why it is
     * `internal` rather than `private`). Returns null when nobody is signed in - [maybeAutoRun]
     * treats that exactly like its own no-op return, never as a zero-progress [Report].
     */
    internal suspend fun runIfSignedIn(context: Context, auth: SupabaseAuth, backend: BodyBackend): Report? {
        if (auth.resolveSignedInUserId() == null) return null
        return run(context, backend)
    }
}
