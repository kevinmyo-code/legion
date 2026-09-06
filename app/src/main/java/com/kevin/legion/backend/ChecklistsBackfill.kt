package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.guardingForeground
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OutboxTarget

/**
 * Per-table install-scoped high-water mark for [ChecklistsBackfill] - the largest local row id
 * already accounted for. Same shape and same reasoning as [LastAspectsBackfillCursor]: the
 * backfill is one-time in effect but runs on every foreground, so it has to become a cheap no-op
 * rather than a full rescan-and-re-push.
 */
internal object ChecklistsBackfillCursor {
    private const val PREFS = "checklists_backfill_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastBackfilledId(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    fun advance(context: Context, table: String, id: Long) {
        prefs(context).edit().putLong(table, id).apply()
    }
}

/**
 * The one-time upload of every checklist row that predates write-through. Write-through only ever
 * pushes NEW writes going forward, so without this the four checklists measured on the A25 (`bio`,
 * `errands`, `Todo`, `Groceries`) - and every item and tick under them - would sit on the phone
 * with no route to an engine whose own checklist tables are empty (confirmed empty by a live
 * `GET /api/changes?aspects=checklists` on 2026-09-06). Same gap, same five rules and the same
 * per-table resumable cursor as [LastAspectsBackfill]:
 *
 * 1. **Idempotent**, because every push is keyed by the row's own `syncId` and the engine's
 *    `_idempotent_or_none` returns the existing row for a repeat rather than making a second one.
 * 2. **Resumable** via [ChecklistsBackfillCursor] - a run that dies halfway resumes at the row it
 *    stopped on rather than starting over.
 * 3. **`serverId` is trusted only in the "already present" direction** - a row that has one is
 *    skipped, a row that lacks one is pushed. The reverse (assuming a missing serverId means the
 *    engine lacks the row) would be unsafe, which is exactly why rule 1 exists to make a
 *    redundant push harmless.
 * 4. **A row deleted locally that never synced is skipped, never resurrected.** Pushing it would
 *    create it on the engine only to tombstone it again on the next pass, and a race between the
 *    two would leave it alive.
 * 5. **A per-table failure stops that table and is reported, without aborting the whole run** - so
 *    one unreachable moment mid-items does not also cost the ticks.
 *
 * **The three tables run in dependency order and stop early.** An item cannot be addressed until
 * its checklist has a server uuid, and a tick until its item does (`checklists/urls.py` nests both),
 * so [ChecklistsPush] pushes a missing parent on demand - and if the checklists pass failed
 * outright, the items pass will simply fail the same way per row rather than silently sending
 * children into nowhere.
 */
object ChecklistsBackfill {
    private data class TableResult(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        val failure: String?,
    )

    data class Report(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        val failed: List<String>,
    )

    private class Row(val id: Long, val syncId: String, val serverId: String?, val deleted: Boolean)

    /** [push] returns null when the row reached the engine, or the sentence saying why it did
     * not. **A message, not a boolean plus a shared `failure` field** - an earlier draft passed a
     * `ChecklistsPush` around all three tables and read its `failure` afterwards, which would have
     * attributed a stale message from an earlier row to a later one on any path that failed
     * WITHOUT setting it (a row present in the list read but gone by the by-id read). Returning the
     * reason with the failure makes that impossible rather than unlikely. */
    private suspend fun backfillTable(
        context: Context,
        table: String,
        rows: List<Row>,
        push: suspend (Row) -> String?,
    ): TableResult {
        val cursorAtStart = ChecklistsBackfillCursor.lastBackfilledId(context, table)
        val pending = rows.filter { it.id > cursorAtStart }.sortedBy { it.id }

        var pushed = 0
        var alreadyPresent = 0
        var skippedLocalOnlyDeleted = 0
        var failure: String? = null

        for (row in pending) {
            when {
                row.serverId != null -> {
                    alreadyPresent++
                    ChecklistsBackfillCursor.advance(context, table, row.id)
                }
                row.deleted -> {
                    skippedLocalOnlyDeleted++
                    ChecklistsBackfillCursor.advance(context, table, row.id)
                }
                else -> {
                    val why = push(row)
                    if (why != null) {
                        failure = "$table: $why (row id ${row.id}, syncId ${row.syncId})"
                        break
                    }
                    pushed++
                    ChecklistsBackfillCursor.advance(context, table, row.id)
                }
            }
        }
        return TableResult(pushed, alreadyPresent, skippedLocalOnlyDeleted, failure)
    }

    /**
     * Each of the three push helpers below builds a FRESH [ChecklistsPush] for the row it is
     * handed - see [backfillTable]'s own doc comment for the stale-message hazard a shared one
     * carries. Constructing one is free (two field assignments); it holds no connection and no
     * state worth reusing across rows.
     *
     * They are separate private functions rather than lambdas inside [run] purely so that function
     * stays under detekt's cyclomatic-complexity ceiling; the behaviour is identical.
     */
    private suspend fun pushChecklistRow(db: CarDatabase, backend: ChecklistsBackend, row: Row): String? {
        val attempt = ChecklistsPush(db, backend)
        val full = db.checklistSyncDao().getByIdIncludingDeleted(row.id)
        return when {
            full == null -> "that checklist is no longer on this device"
            attempt.checklistServerId(full) != null -> null
            else -> attempt.failure?.message ?: "unknown error"
        }
    }

    private suspend fun pushItemRow(db: CarDatabase, backend: ChecklistsBackend, row: Row): String? {
        val attempt = ChecklistsPush(db, backend)
        val full = db.checklistItemDao().getByIdIncludingDeleted(row.id)
        return when {
            full == null -> "that checklist item is no longer on this device"
            attempt.itemServerIds(full) != null -> null
            else -> attempt.failure?.message ?: "unknown error"
        }
    }

    private suspend fun pushTickRow(db: CarDatabase, backend: ChecklistsBackend, row: Row): String? {
        val attempt = ChecklistsPush(db, backend)
        val tick = db.checklistTickSyncDao().getAll().firstOrNull { it.id == row.id }
        val item = tick?.let { db.checklistItemDao().getByIdIncludingDeleted(it.itemId) }
        return when {
            tick == null || item == null -> "that tick's item is no longer on this device"
            attempt.pushTick(item, tick) -> null
            else -> attempt.failure?.message ?: "unknown error"
        }
    }

    suspend fun run(context: Context, backend: ChecklistsBackend): Report {
        val db = CarDatabase.getDatabase(context)

        val checklistResult = backfillTable(
            context,
            OutboxTarget.CHECKLISTS,
            db.checklistSyncDao().getAll().map { Row(it.id, it.syncId, it.serverId, it.deleted) },
        ) { row -> pushChecklistRow(db, backend, row) }

        val itemResult = backfillTable(
            context,
            OutboxTarget.CHECKLIST_ITEMS,
            db.checklistItemSyncDao().getAll().map { Row(it.id, it.syncId, it.serverId, it.deleted) },
        ) { row -> pushItemRow(db, backend, row) }

        val tickResult = backfillTable(
            context,
            OutboxTarget.CHECKLIST_TICKS,
            // A soft-deleted tick with no serverId is an untick of something the engine never
            // held - rule 4, and here it is the ordinary case rather than an edge one, since
            // ticking then unticking the same day before ever syncing is a normal Tuesday.
            db.checklistTickSyncDao().getAll().map { Row(it.id, it.syncId, it.serverId, it.deleted) },
        ) { row -> pushTickRow(db, backend, row) }

        val results = listOf(checklistResult, itemResult, tickResult)
        return Report(
            pushed = results.sumOf { it.pushed },
            alreadyPresent = results.sumOf { it.alreadyPresent },
            skippedLocalOnlyDeleted = results.sumOf { it.skippedLocalOnlyDeleted },
            failed = results.mapNotNull { it.failure },
        )
    }

    // --- Foreground auto-trigger ---------------------------------------------------------------

    @Volatile private var lastAutoRunAt = 0L
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** `MainActivity.onResume`'s hook - runs after the drain and BEFORE the pull, the same
     * ordering [LastAspectsBackfill.maybeAutoRun] uses: an unsent local row must reach the engine
     * before the pull weighs last-write-wins against an engine copy that does not know about it
     * yet. No-ops silently when checklists are not on the Django transport or nobody is signed in. */
    suspend fun maybeAutoRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val backend = EngineBackends(app).checklistsBackend() ?: return
        lastAutoRunAt = now
        guardingForeground(onFailure = { MidnightEvents.checklistsBackfillFailed(it) }) {
            val report = run(app, backend)
            MidnightEvents.checklistsBackfillSucceeded(
                report.pushed,
                report.alreadyPresent,
                report.skippedLocalOnlyDeleted,
                report.failed,
            )
        }
    }
}
