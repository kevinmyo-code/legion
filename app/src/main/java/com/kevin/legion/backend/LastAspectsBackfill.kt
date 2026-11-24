package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.OutboxTarget

/**
 * Per-table install-scoped high-water mark for [LastAspectsBackfill]'s one-time (then perpetually
 * cheap-no-op) upload - same shape as [LedgerConfigBackfillCursor]. See [BodyBackfill]'s own class
 * doc for the full reasoning; unchanged here.
 */
internal object LastAspectsBackfillCursor {
    private const val PREFS = "last_aspects_backfill_cursor"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastBackfilledId(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    fun advance(context: Context, table: String, id: Long) {
        prefs(context).edit().putLong(table, id).apply()
    }
}

/**
 * The one-time backfill for live-sync's last aspect slice: every local row that predates
 * write-through (`LastAspectsOutbox.kt`) has no path to the server at all, because write-through
 * only ever pushes NEW writes going forward - same gap [LedgerConfigBackfill]'s own class doc
 * describes, same five numbered rules apply (idempotent via the reused `syncId`, resumable via
 * [LastAspectsBackfillCursor], `serverId` trusted only in the "already present" direction, a
 * locally-deleted-and-never-synced row is skipped rather than resurrected, per-table failures do
 * not abort the whole run). This is the map's own 4 (`goals`) rows, every one of which predates
 * this migration entirely.
 *
 * **NARROWED by the lists redesign slice 1a (2026-11-24)** to `goals` alone, with
 * `item_lists`/`list_items`/`grocery_staples` - see [LastAspectsBackend]'s own class doc for which
 * reader or writer each one lost. The parent-before-child ordering `item_lists` needed went with
 * them; one table has no ordering to get wrong.
 */
object LastAspectsBackfill {
    private data class TableResult(val pushed: Int, val alreadyPresent: Int, val skippedLocalOnlyDeleted: Int, val failure: String?)

    data class Report(val pushed: Int, val alreadyPresent: Int, val skippedLocalOnlyDeleted: Int, val failed: List<String>)

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
        val cursorAtStart = LastAspectsBackfillCursor.lastBackfilledId(context, table)
        val pending = rows.filter { localId(it) > cursorAtStart }.sortedBy { localId(it) }

        var pushed = 0
        var alreadyPresent = 0
        var skippedLocalOnlyDeleted = 0
        var failure: String? = null

        for (row in pending) {
            when {
                localServerId(row) != null -> {
                    alreadyPresent++
                    LastAspectsBackfillCursor.advance(context, table, localId(row))
                }
                localDeleted(row) -> {
                    skippedLocalOnlyDeleted++
                    LastAspectsBackfillCursor.advance(context, table, localId(row))
                }
                else -> {
                    val result = push(row)
                    if (result.isSuccess) {
                        pushed++
                        LastAspectsBackfillCursor.advance(context, table, localId(row))
                    } else {
                        failure = "$table: ${result.exceptionOrNull()?.message ?: "unknown error"} (row id ${localId(row)}, guid ${localGuid(row)})"
                        break
                    }
                }
            }
        }
        return TableResult(pushed, alreadyPresent, skippedLocalOnlyDeleted, failure)
    }

    suspend fun run(context: Context, backend: LastAspectsBackend): Report {
        val db = CarDatabase.getDatabase(context)

        val goals = backfillTable(
            context, OutboxTarget.GOALS, db.goalDao().getAllIncludingDeleted(),
            localId = { it.id }, localGuid = { it.syncId }, localServerId = { it.serverId }, localDeleted = { it.deleted },
            push = { row: Goal ->
                val supersedesGuid = row.supersedesId?.let { priorId -> db.goalDao().history(row.lineageId).firstOrNull { it.id == priorId }?.syncId }
                backend.upsertGoal(row.syncId, GoalFields(row.lineageId, row.aspect, row.statement, row.targetValue, row.unit, row.metricKey, row.deadlineEpoch, row.status, supersedesGuid, row.closedAt, row.createdAt))
            },
        )
        val results = listOf(goals)
        return Report(
            pushed = results.sumOf { it.pushed },
            alreadyPresent = results.sumOf { it.alreadyPresent },
            skippedLocalOnlyDeleted = results.sumOf { it.skippedLocalOnlyDeleted },
            failed = results.mapNotNull { it.failure },
        )
    }

    // --- Foreground auto-trigger ------------------------------------------------------------------

    @Volatile private var lastAutoRunAt = 0L
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** `MainActivity.onResume`'s hook - runs BEFORE [LastAspectsSync.maybeAutoPull], same
     * reasoning as [LedgerConfigBackfill.maybeAutoRun]'s own class doc. No-ops silently when
     * Supabase is not configured or nobody is signed in. */
    suspend fun maybeAutoRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoRunAt = now
        try {
            val report = runIfSignedIn(app, SupabaseAuth(app), SupabaseLastAspectsBackend(client)) ?: return
            MidnightEvents.lastAspectsBackfillSucceeded(report.pushed, report.alreadyPresent, report.skippedLocalOnlyDeleted, report.failed)
        } catch (e: Exception) {
            MidnightEvents.lastAspectsBackfillFailed(e)
        }
    }

    internal suspend fun runIfSignedIn(context: Context, auth: SupabaseAuth, backend: LastAspectsBackend): Report? {
        if (auth.resolveSignedInUserId() == null) return null
        return run(context, backend)
    }
}
