package com.kevin.legion.engine.migration

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.ledger.LedgerRecordBridge

/**
 * The one-time, idempotent copier that carries Wave 3's live data
 * (`.scratch/aspect-engine/issues/21-migration-waves.md` point 2 - "then ledger (gate re-plumb)") -
 * every [com.kevin.legion.data.local.LedgerTransaction] - onto the engine through
 * [RecordStore], the engine's single write door. **Additive-only for the COPY half**: reads the
 * legacy `ledger_transactions` table, writes new [com.kevin.legion.data.local.EngineRecord] rows;
 * never touches, drops, or mutates the legacy table itself.
 *
 * **Cutover 3** (`docs/architecture/cutover3-2026-08-24.md`) repointed every WRITE at
 * [RecordStore] - `ledger/IngestPipeline`'s commit path, `ledger/LedgerController`'s pending-log
 * write - so this object's job narrows to exactly what its name says: the one-time COPY of
 * pre-cutover legacy data, plus [catchUpOnce]'s single post-cutover reconciliation pass (see below).
 * It is no longer the ongoing bridge Wave 3 originally built it as - the legacy `ledger_transactions`
 * table is now FROZEN (zero writers, per the cutover doc's reader/writer table), so from this branch
 * forward nothing NEW ever needs copying, and nothing NEW ever supersedes a legacy row either.
 *
 * The exact field mapping is `docs/architecture/wave3-carve-2026-08-23.md`, applied through
 * [LedgerRecordBridge] (extracted at cutover so [com.kevin.legion.ledger.IngestPipeline] and
 * [com.kevin.legion.ledger.LedgerController] use the identical map, not a second one). Its headline
 * finding: **[com.kevin.legion.data.local.LedgerTransaction.ingestMethod] maps DIRECTLY onto
 * [RecordProvenance]** - `DETERMINISTIC`/`LLM_RECONCILED`/`UNRECONCILED` are the same three words in
 * both enums, so [LedgerRecordBridge.provenanceFor] is a plain, exhaustive `when` with no `else`
 * branch and no upgrading/collapsing of any value.
 *
 * **[reconcileSupersededProvisional] - retired from the per-call hot path at cutover, kept for
 * [catchUpOnce] only.** CLAUDE.md §4 rule 7 requires an [com.kevin.legion.data.local.IngestMethod.UNRECONCILED]
 * row to be transient - deleted the moment a reconciled file later covers the same account/dates.
 * Before cutover, that supersession happened on the LEGACY table
 * ([com.kevin.legion.data.local.LedgerTransactionDao.deleteSupersededProvisional], called from
 * `ledger/IngestPipeline.commit`) and a purely additive copier could not mirror that DELETION on its
 * own, so this pass ran on every single call (including the fast path) to catch up. **Cutover moves
 * supersession itself into `IngestPipeline.commit`'s own engine-side transaction** - see that
 * function's doc comment - so a NEWLY superseded provisional row is trashed at write time, in the
 * SAME transaction as the write that supersedes it, and this reconciliation pass is no longer needed
 * on every call. It is NOT deleted, because it still has exactly one real job left: catching any
 * supersession that happened on the legacy table BEFORE this cutover branch's code went live (i.e.
 * between whenever the engine mirror last ran and the moment this build starts running) - that one
 * historical gap is what [catchUpOnce] exists to close, once, not on a schedule.
 */
object EngineDataMigrationWave3 {
    private const val PREFS = "engine_migration_wave3"
    private const val KEY_TRANSACTIONS_COMPLETED = "transactions_completed_v1"
    private const val KEY_CUTOVER3_CATCHUP_COMPLETED = "cutover3_catchup_v1_completed"

    /** [copied] counts only rows actually written this call - a row skipped because its `guid`
     * already existed (the per-row idempotency backstop) is not counted twice across retries. */
    data class Result(val copied: Int, val alreadyDone: Boolean)

    private fun store(db: CarDatabase) = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    suspend fun copyLedgerIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TRANSACTIONS_COMPLETED, false)) {
            return Result(copied = 0, alreadyDone = true)
        }

        val db = CarDatabase.getDatabase(context)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val transactions = db.ledgerTransactionDao().getAll()

        var copied = 0
        // Same "a check that passes when nothing parsed is not a gate" posture Wave 2's
        // [failedLineItemGuids] applies to migration completeness - every Failure this pass sees is
        // collected explicitly and folded into the completion check below, never re-derived only
        // from "does a guid now exist".
        var anyFailure = false

        for (txn in transactions) {
            val guid = txn.syncId
            if (db.engineRecordDao().getByGuid(guid) != null) continue // already copied by an earlier, interrupted pass

            // Neither LedgerTransaction carries an insert-time clock of its own (no
            // createdAt/updatedAt column) - txnDate is the closest available anchor for "when did
            // this data become true", same substitution reasoning Wave 2's carve doc states for
            // PantryReceipt.purchaseDate.
            val result = recordStore.create(
                recordTypeId = schema.transaction.recordTypeId,
                fieldValues = LedgerRecordBridge.fieldValuesFor(txn, schema.transaction.fieldIds),
                provenance = LedgerRecordBridge.provenanceFor(txn.ingestMethod),
                now = txn.txnDate,
                guid = guid,
            )
            when (result) {
                is RecordStore.WriteResult.Success -> copied++
                is RecordStore.WriteResult.Failure -> anyFailure = true
            }
        }

        if (!anyFailure) prefs.edit().putBoolean(KEY_TRANSACTIONS_COMPLETED, true).apply()
        return Result(copied = copied, alreadyDone = false)
    }

    /**
     * The ONE post-cutover reconciliation run (point 4 of the cutover instructions) - re-runs the
     * additive copy (idempotent, guid-keyed, picks up anything written to the legacy table before
     * this build's `IngestPipeline` took over) and then [reconcileSupersededProvisional] exactly
     * once, to trash any engine-side `UNRECONCILED` mirror whose legacy row was already superseded
     * before cutover. Guarded by its own SharedPreferences marker so it runs at most once ever - same
     * shape as [EngineDataMigrationWave1.catchUpOnce]/[EngineDataMigrationWave2.catchUpOnce]. Safe to
     * call from `MidnightApplication.onCreate` alongside the ordinary [copyLedgerIfNeeded] call.
     *
     * **No id-space rekey needed here, unlike cutover 1's `ListItemSkip.itemId`** - checked, not
     * assumed. Grepped this codebase for anything keying off a legacy `LedgerTransaction.id`: nothing
     * found. `LedgerTransaction.lineRef`/`sourceFileId`/`syncId` are the only cross-referencing
     * fields any code reads, and none of them is the Room-assigned autoincrement `id` - `syncId`
     * (the `guid`) is what identity already rides on, exactly the same as cutover 1/2's own precedent
     * for a genuinely id-space-free entity.
     */
    suspend fun catchUpOnce(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CUTOVER3_CATCHUP_COMPLETED, false)) return

        // Clears the ordinary fast-path flag so copyLedgerIfNeeded does a genuine rescan rather than
        // taking its own completion shortcut - same "clear then re-run" shape as
        // EngineDataMigrationWave1.catchUpOnce/EngineDataMigrationWave2.catchUpOnce.
        prefs.edit().putBoolean(KEY_TRANSACTIONS_COMPLETED, false).apply()
        copyLedgerIfNeeded(context)
        reconcileSupersededProvisional(context)

        // Only set the marker once BOTH the rescan and the reconciliation pass above have completed
        // - a crash between them leaves the marker unset and the whole catch-up retries next start,
        // same crash-safety shape as every other catchUpOnce in this codebase.
        prefs.edit().putBoolean(KEY_CUTOVER3_CATCHUP_COMPLETED, true).apply()
    }

    /**
     * See this object's own class doc for why this exists. Compares the LIVE set of legacy
     * `LedgerTransaction.syncId`s against every currently-active engine `Transaction` record tagged
     * [RecordProvenance.UNRECONCILED], and [RecordStore.delete]s (trashes, 30-day restorable) any
     * whose legacy row is gone. Never touches a [RecordProvenance.DETERMINISTIC]/
     * [RecordProvenance.LLM_RECONCILED] record - see the class doc's scoping note.
     *
     * Reads the WHOLE `Transaction` record-type table rather than a narrower query, same "personal
     * app's data volume, not an enterprise table scan" tradeoff [RecordStore]'s own class doc
     * already accepts for its aggregate recompute.
     */
    private suspend fun reconcileSupersededProvisional(context: Context): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)

        val liveGuids = db.ledgerTransactionDao().allSyncIds().toSet()
        val engineRows = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId)

        var trashed = 0
        for (row in engineRows) {
            if (row.provenance != RecordProvenance.UNRECONCILED) continue
            if (row.guid in liveGuids) continue // legacy row still present - not superseded
            val result = recordStore.delete(row.id)
            if (result is RecordStore.DeleteResult.Trashed) trashed++
        }
        return trashed
    }

    /** App-start convenience, wrapped so a failure here can never cost anything else - same L12
     * "independent failure mode" reasoning [EngineDataMigrationWave1.runAll]/[EngineDataMigrationWave2.runAll]
     * already use. Runs the ordinary copy AND the one-time [catchUpOnce] reconciliation - both are
     * idempotent and cheap to call on every app start. */
    suspend fun runAll(context: Context) {
        runCatching { copyLedgerIfNeeded(context) }
        runCatching { catchUpOnce(context) }
    }
}
