package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.engineRefusalSentence
import com.kevin.legion.backend.engine.guardingForeground
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OutboxTarget

/**
 * Per-table install-scoped high-water mark for [ChecklistsBackfill] - the largest local row id
 * already accounted for. Same shape and same reasoning as [LastAspectsBackfillCursor]: the
 * backfill is one-time in effect but runs on every foreground, so it has to become a cheap no-op
 * rather than a full rescan-and-re-push.
 *
 * **It also records the rows the engine permanently refused, and that second job is not
 * decoration.** The high-water mark alone already stops a refused row being retried (it is
 * advanced past one, exactly as it is past an already-present or locally-deleted row), so nothing
 * here is load-bearing for the RETRY. What it is load-bearing for is the SENTENCE: without it, the
 * one run that met the refusal could say "skipped 1" and every run afterwards would report a clean
 * "backfilled 0" while a tick sits on the phone that will never cross. A row nobody will ever send
 * again is a fact about this install, not an event in one pass, so it is stored like one.
 */
internal object ChecklistsBackfillCursor {
    private const val PREFS = "checklists_backfill_cursor"
    private const val UNSYNCABLE_PREFIX = "unsyncable_"

    /** Separates the row id from its reason inside one stored string. ASCII 31, "unit separator" -
     * chosen because the reason is the ENGINE'S own English sentence and may contain any
     * punctuation a human would write, including the tabs, colons and quote marks a more obvious
     * delimiter would collide with. */
    private const val FIELD_SEPARATOR = '\u001F'

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastBackfilledId(context: Context, table: String): Long = prefs(context).getLong(table, 0L)

    fun advance(context: Context, table: String, id: Long) {
        prefs(context).edit().putLong(table, id).apply()
    }

    /** Rows this install will never send, by local row id, with the reason the engine gave. */
    fun unsyncable(context: Context, table: String): Map<Long, String> =
        prefs(context).getStringSet(UNSYNCABLE_PREFIX + table, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val at = entry.indexOf(FIELD_SEPARATOR)
                if (at <= 0) null else entry.substring(0, at).toLongOrNull()?.let { it to entry.substring(at + 1) }
            }
            .toMap()

    /** Idempotent: recording the same row twice replaces its reason rather than adding a second
     * entry, because the set is keyed by the whole string and a re-refusal could word itself
     * differently. */
    fun recordUnsyncable(context: Context, table: String, id: Long, reason: String) {
        val key = UNSYNCABLE_PREFIX + table
        val kept = unsyncable(context, table).toMutableMap()
        kept[id] = reason
        prefs(context).edit()
            .putStringSet(key, kept.map { (rowId, why) -> "$rowId$FIELD_SEPARATOR$why" }.toSet())
            .apply()
    }

    fun unsyncableCount(context: Context): Int =
        BACKFILL_TABLES.sumOf { unsyncable(context, it).size }

    /** Test-only. Robolectric's `SharedPreferences` outlive a `CarDatabase` reset, so without this
     * a cursor left at row 2 by one test silently skips rows 1 and 2 of the next - which would not
     * fail loudly, it would just make a backfill test assert on a backfill that never examined
     * anything. */
    fun resetForTest(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** The three tables this backfill walks, in the dependency order [ChecklistsBackfill.run]
     * needs them - named once here so the count above cannot drift from the loop below. */
    val BACKFILL_TABLES = listOf(
        OutboxTarget.CHECKLISTS,
        OutboxTarget.CHECKLIST_ITEMS,
        OutboxTarget.CHECKLIST_TICKS,
    )
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
 * 5. **An UNREACHABLE engine stops that table and is reported, without aborting the whole run** -
 *    so one unreachable moment mid-items does not also cost the ticks.
 *
 * 6. **A REFUSED row is skipped, recorded, and the run carries on. ADDED 2026-09-06, and it is the
 *    correction of a real defect, so what rule 5 used to say is worth keeping visible: it read
 *    "a per-table failure stops that table", with no distinction between the two kinds of
 *    failure.** On the A25 that meant the very first tick the engine refused - `bio`'s
 *    "3 sets goblet squats", ticked before it was ever given a unit, so a legacy tick exists with
 *    `value = null` on an item that is now measured - stopped the ticks table dead. **Zero ticks
 *    were ever backfilled**, and the refusal reprinted on the Setup screen on every subsequent
 *    sync as a JSON blob. The engine's constraint is right and the data is genuinely unsendable;
 *    what was wrong was letting one such row hold every other row hostage.
 *
 *    **The refused row's history is KEPT, locally, and never sent (Kevin's ruling, verbatim: "keep
 *    it locally, never send it, and say so once in the sentence. Do not delete the user's history
 *    to make a sync clean").** He did do those goblet squats; the tick is a true record of an act,
 *    and the only thing wrong with it is that a rule invented afterwards makes it unrepresentable
 *    on the engine. Nothing here deletes it, tombstones it, or writes a `deleted` flag - a
 *    tombstone would say the tick did not happen, which is a different and false claim. It is
 *    simply left out of the backfill set, with the engine's own words recorded beside it.
 *
 *    **Why "leave it out of the set" rather than an `unsyncable` column on the row.** A column
 *    would be a Room migration, a schema version bump and a migration test to record something
 *    that is not a property of the tick at all - it is a property of THIS install's relationship
 *    with THIS engine, and it would read as a fact about the user's data forever after. The
 *    high-water cursor already excludes the row for free (it advances past a refusal exactly as it
 *    does past an already-present row), and [ChecklistsBackfillCursor.recordUnsyncable] keeps the
 *    reason so the sentence can still say it on later runs.
 *
 * **The three tables run in dependency order and stop early.** An item cannot be addressed until
 * its checklist has a server uuid, and a tick until its item does (`checklists/urls.py` nests both),
 * so [ChecklistsPush] pushes a missing parent on demand - and if the checklists pass failed
 * outright, the items pass will simply fail the same way per row rather than silently sending
 * children into nowhere.
 */
object ChecklistsBackfill {

    /** What one row's push did, from the BACKFILL'S point of view rather than the transport's -
     * the three things this loop can do next, named as those three things.
     *
     * [Skip] is any permanent per-row refusal: the engine said no in words
     * ([EngineFailure.Refused]), or the row vanished from this device between the list read and
     * the by-id read. Both mean "this row, never; the others, carry on".
     *
     * [Stop] is everything else - unreachable, unauthorized, a reply that did not decode. All
     * three are conditions of the RUN, not of the row, so continuing would burn a request per
     * remaining row to learn the same thing. */
    private sealed interface PushOutcome {
        object Sent : PushOutcome
        data class Skip(val reason: String) : PushOutcome
        data class Stop(val reason: String) : PushOutcome
    }

    /** One row the engine will not take, kept so the summary sentence can name it. */
    data class Skipped(val table: String, val rowId: Long, val syncId: String, val reason: String)

    private data class TableResult(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        val skipped: List<Skipped>,
        val stopped: String?,
    )

    /**
     * @param skipped rows refused on THIS run - empty on every run after the first that met them.
     * @param unsyncableTotal every row this install has ever had refused, this run's included. The
     *   number that keeps being true, so a later run can still say a tick is being kept back
     *   instead of reporting a clean pass.
     * @param stopped a table that stopped early on an unreachable/unauthorized engine (rule 5).
     *   **RENAMED from `failed` 2026-09-06**: it used to carry refusals too, which is what made
     *   one refused tick look like a broken sync forever.
     */
    data class Report(
        val pushed: Int,
        val alreadyPresent: Int,
        val skippedLocalOnlyDeleted: Int,
        val skipped: List<Skipped>,
        val unsyncableTotal: Int,
        val stopped: List<String>,
    )

    private class Row(val id: Long, val syncId: String, val serverId: String?, val deleted: Boolean)

    /** Mutable tally for one table's pass - a holder rather than five locals threaded through
     * [recordOutcome], which is what keeps [backfillTable] itself under detekt's
     * cyclomatic-complexity ceiling (the same reason the three push helpers below are separate
     * functions). */
    private class Tally {
        var pushed = 0
        var alreadyPresent = 0
        var skippedLocalOnlyDeleted = 0
        val skipped = mutableListOf<Skipped>()
        var stopped: String? = null
    }

    /** [push] says what happened to the row. **An outcome, not a boolean plus a shared `failure`
     * field** - an earlier draft passed a `ChecklistsPush` around all three tables and read its
     * `failure` afterwards, which would have attributed a stale message from an earlier row to a
     * later one on any path that failed WITHOUT setting it (a row present in the list read but
     * gone by the by-id read). Returning the reason with the failure makes that impossible rather
     * than unlikely. */
    private suspend fun backfillTable(
        context: Context,
        table: String,
        rows: List<Row>,
        push: suspend (Row) -> PushOutcome,
    ): TableResult {
        val cursorAtStart = ChecklistsBackfillCursor.lastBackfilledId(context, table)
        val pending = rows.filter { it.id > cursorAtStart }.sortedBy { it.id }
        val tally = Tally()

        for (row in pending) {
            when {
                row.serverId != null -> {
                    tally.alreadyPresent++
                    ChecklistsBackfillCursor.advance(context, table, row.id)
                }
                row.deleted -> {
                    tally.skippedLocalOnlyDeleted++
                    ChecklistsBackfillCursor.advance(context, table, row.id)
                }
                else -> if (!recordOutcome(context, table, row, push(row), tally)) break
            }
        }
        return TableResult(
            tally.pushed,
            tally.alreadyPresent,
            tally.skippedLocalOnlyDeleted,
            tally.skipped.toList(),
            tally.stopped,
        )
    }

    /** Applies one row's [outcome] to [tally] and to the cursor. Returns false only for
     * [PushOutcome.Stop], the one outcome that ends the table's pass.
     *
     * **The cursor advances on a [PushOutcome.Skip] as well as on a [PushOutcome.Sent], and that
     * line is what makes rule 6 hold.** Without it, a refused row that happens to be the LAST
     * pending row in its table would never be passed by any later row's advance, and would be
     * retried on every foreground forever - the exact "error blob repeated on every sync" this
     * change exists to end, merely quieter. */
    private fun recordOutcome(
        context: Context,
        table: String,
        row: Row,
        outcome: PushOutcome,
        tally: Tally,
    ): Boolean {
        when (outcome) {
            is PushOutcome.Sent -> {
                tally.pushed++
                ChecklistsBackfillCursor.advance(context, table, row.id)
            }
            is PushOutcome.Skip -> {
                tally.skipped += Skipped(table, row.id, row.syncId, outcome.reason)
                ChecklistsBackfillCursor.recordUnsyncable(context, table, row.id, outcome.reason)
                ChecklistsBackfillCursor.advance(context, table, row.id)
            }
            is PushOutcome.Stop -> {
                tally.stopped = "$table: ${outcome.reason} (row id ${row.id}, syncId ${row.syncId})"
                return false
            }
        }
        return true
    }

    /** Sorts a [ChecklistsPush] failure into "this row" and "this run" - see [PushOutcome]'s own
     * doc comment for the two buckets and why the line falls where it does. The engine's refusal
     * is unwrapped out of DRF's `{"non_field_errors": [...]}` envelope for the SENTENCE only
     * ([engineRefusalSentence]); nothing here rewords it, and the verbatim body is still what
     * `ChecklistsWriteThrough` hands a user who tapped tick. */
    private fun classify(cause: Throwable?): PushOutcome =
        when (val failure = (cause as? EngineHttpException)?.failure) {
            is EngineFailure.Refused -> PushOutcome.Skip(engineRefusalSentence(failure.body))
            null -> PushOutcome.Stop(cause?.message ?: "unknown error")
            else -> PushOutcome.Stop(failure.sentence)
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
    private suspend fun pushChecklistRow(db: CarDatabase, backend: ChecklistsBackend, row: Row): PushOutcome {
        val attempt = ChecklistsPush(db, backend)
        val full = db.checklistSyncDao().getByIdIncludingDeleted(row.id)
        return when {
            full == null -> PushOutcome.Skip("that checklist is no longer on this device")
            attempt.checklistServerId(full) != null -> PushOutcome.Sent
            else -> classify(attempt.failure)
        }
    }

    private suspend fun pushItemRow(db: CarDatabase, backend: ChecklistsBackend, row: Row): PushOutcome {
        val attempt = ChecklistsPush(db, backend)
        val full = db.checklistItemDao().getByIdIncludingDeleted(row.id)
        return when {
            full == null -> PushOutcome.Skip("that checklist item is no longer on this device")
            attempt.itemServerIds(full) != null -> PushOutcome.Sent
            else -> classify(attempt.failure)
        }
    }

    private suspend fun pushTickRow(db: CarDatabase, backend: ChecklistsBackend, row: Row): PushOutcome {
        val attempt = ChecklistsPush(db, backend)
        val tick = db.checklistTickSyncDao().getAll().firstOrNull { it.id == row.id }
        val item = tick?.let { db.checklistItemDao().getByIdIncludingDeleted(it.itemId) }
        return when {
            tick == null || item == null -> PushOutcome.Skip("that tick's item is no longer on this device")
            attempt.pushTick(item, tick) -> PushOutcome.Sent
            else -> classify(attempt.failure)
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
            skipped = results.flatMap { it.skipped },
            // Read AFTER the three passes, so it already includes anything they just recorded.
            unsyncableTotal = ChecklistsBackfillCursor.unsyncableCount(context),
            stopped = results.mapNotNull { it.stopped },
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
                report.skipped.map { "${it.table} row ${it.rowId}: ${it.reason}" },
                report.unsyncableTotal,
                report.stopped,
            )
        }
    }
}
