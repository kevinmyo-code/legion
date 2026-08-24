package com.kevin.legion.engine.migration

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder

/**
 * The one-time, idempotent copier that carries Wave 3's live data
 * (`.scratch/aspect-engine/issues/21-migration-waves.md` point 2 - "then ledger (gate re-plumb)") -
 * every [com.kevin.legion.data.local.LedgerTransaction] - onto the engine through
 * [RecordStore], the engine's single write door. **Additive-only for the COPY half**: reads the
 * legacy `ledger_transactions` table, writes new [com.kevin.legion.data.local.EngineRecord] rows;
 * never touches, drops, or mutates the legacy table itself. The old table,
 * `ledger/LedgerController`, `ledger/IngestPipeline`, and every old ledger tool/screen keep working
 * unchanged - cutover (repointing WRITES at [RecordStore]) is a later, per-aspect wave (ticket 14
 * point 2), not this one. Same shape as [EngineDataMigrationWave1]/[EngineDataMigrationWave2] - see
 * those objects' own class docs for the two-layer idempotency reasoning (completion flag + per-row
 * `guid` backstop) this one reuses rather than re-explaining.
 *
 * The exact field mapping is `docs/architecture/wave3-carve-2026-08-23.md`. Its headline finding:
 * **[com.kevin.legion.data.local.LedgerTransaction.ingestMethod] maps DIRECTLY onto
 * [RecordProvenance]** - `DETERMINISTIC`/`LLM_RECONCILED`/`UNRECONCILED` are the same three words in
 * both enums, so [provenanceFor] is a plain, exhaustive `when` with no `else` branch and no
 * upgrading/collapsing of any value. A [com.kevin.legion.data.local.LedgerTransaction] Kotlin
 * instance can never carry a fourth `ingestMethod` value in the first place - Room's own enum
 * column handling calls `IngestMethod.valueOf` while reading the row and throws before this copier
 * ever sees it, so "a value this `when` doesn't recognise" is not a runtime case this function has
 * to defend against; it is a compile-time impossibility once the row exists as a Kotlin object.
 *
 * **This wave adds a THIRD behaviour Wave 1/2 did not need: [reconcileSupersededProvisional].**
 * CLAUDE.md §4 rule 7 requires an [IngestMethod.UNRECONCILED] row to be transient - deleted the
 * moment a reconciled file later covers the same account/dates
 * ([com.kevin.legion.data.local.LedgerTransactionDao.deleteSupersededProvisional], called from
 * `ledger/IngestPipeline.commit`, which keeps operating on the LEGACY table only - see the carve
 * doc's dual-copy plan for why). A purely additive copier (Wave 1/2's shape) cannot mirror a
 * DELETION - it only ever adds rows a `guid` check has not seen yet - so without this pass, an
 * engine-side `UNRECONCILED` row copied from a provisional CSV import would silently outlive its
 * own legacy row once a later reconciled statement superseded and deleted it there, which is
 * exactly the "outlive or double-count against the verified row that supersedes it" failure rule 7
 * exists to prevent. [reconcileSupersededProvisional] closes that gap the only way an additive-only
 * copier can: by comparing the LIVE set of legacy `syncId`s against every engine-side
 * `UNRECONCILED` `Transaction` record and trashing (never hard-deleting - matches
 * [RecordStore.delete]'s own 30-day-restorable trash semantics) any whose legacy row is gone.
 * **Scoped deliberately to [RecordProvenance.UNRECONCILED] only** - a `DETERMINISTIC`/
 * `LLM_RECONCILED` row can also be deleted from the legacy table (a replace-flow re-import, or
 * `LedgerController.purgeAll`), and this wave does NOT propagate those deletions; that gap is named,
 * not silently accepted, in the carve doc's "owed follow-ups" section, because rule 7's specific
 * transience guarantee is the one this migration is obligated to keep true and the one this pass
 * actually keeps true.
 */
object EngineDataMigrationWave3 {
    private const val PREFS = "engine_migration_wave3"
    private const val KEY_TRANSACTIONS_COMPLETED = "transactions_completed_v1"

    /** [copied] counts only rows actually written this call - a row skipped because its `guid`
     * already existed (the per-row idempotency backstop) is not counted twice across retries.
     * [supersededTrashed] counts engine-side [RecordProvenance.UNRECONCILED] rows trashed this call
     * because their legacy row is now gone (see [reconcileSupersededProvisional]'s doc comment) -
     * **this can be non-zero even when [alreadyDone] is true**, a deliberate departure from Wave
     * 1/2's fast-path shape: rule 7's transience guarantee has to keep holding on every call, not
     * just the first one, because `ledger/IngestPipeline` keeps deleting superseded provisional
     * rows from the legacy table on every later app run, long after this copier's own completion
     * flag was set. */
    data class Result(val copied: Int, val supersededTrashed: Int, val alreadyDone: Boolean)

    private fun store(db: CarDatabase) = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    /** [com.kevin.legion.data.local.IngestMethod] -> [RecordProvenance], one-for-one, exhaustive,
     * no `else`. See this object's own class doc for why a fourth value can never reach this
     * function. */
    private fun provenanceFor(ingestMethod: IngestMethod): RecordProvenance = when (ingestMethod) {
        IngestMethod.DETERMINISTIC -> RecordProvenance.DETERMINISTIC
        IngestMethod.LLM_RECONCILED -> RecordProvenance.LLM_RECONCILED
        IngestMethod.UNRECONCILED -> RecordProvenance.UNRECONCILED
    }

    suspend fun copyLedgerIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_TRANSACTIONS_COMPLETED, false)) {
            // The additive copy itself is done, but rule 7's transience guarantee is not a
            // one-time fact - it must keep holding on every call. See this object's own class doc
            // and [Result]'s own doc comment for why this fast path still runs the reconciliation
            // pass instead of returning immediately the way Wave 1/2's fast path does.
            val trashed = reconcileSupersededProvisional(context)
            return Result(copied = 0, supersededTrashed = trashed, alreadyDone = true)
        }

        val db = CarDatabase.getDatabase(context)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val transactions = db.ledgerTransactionDao().getAll()

        var copied = 0
        // Same "a check that passes when nothing parsed is not a gate" posture Wave 2's
        // [failedLineItemGuids] applies to migration completeness - every Failure this pass sees is
        // collected explicitly and folded into the completion check below, never re-derived only
        // from "does a guid now exist", which would miss nothing here (Transaction has no child
        // record type to lose track of the way Wave 2's LineItem could) but is kept as the same
        // explicit shape for consistency and because the very next field this class added
        // ([reconcileSupersededProvisional]) reads engine state that a silent miss here would corrupt.
        var anyFailure = false

        for (txn in transactions) {
            val guid = txn.syncId
            if (db.engineRecordDao().getByGuid(guid) != null) continue // already copied by an earlier, interrupted pass

            val fieldValues: Map<Long, Any?> = mapOf(
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE) to txn.sourceFile,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_ACCOUNT_ID) to txn.accountId,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_CURRENCY) to txn.currency.name,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_TXN_DATE) to txn.txnDate,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_DESCRIPTION) to txn.description,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_AMOUNT) to txn.amountCents,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_BALANCE) to txn.balanceCents,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_LINE_REF) to txn.lineRef,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE_ID) to txn.sourceFileId,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY) to txn.category,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY_PENDING) to txn.categoryPending,
                schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_PENDING_LOGGED_AT) to txn.pendingLoggedAt,
            )

            // Neither LedgerTransaction carries an insert-time clock of its own (no
            // createdAt/updatedAt column) - txnDate is the closest available anchor for "when did
            // this data become true", same substitution reasoning Wave 2's carve doc states for
            // PantryReceipt.purchaseDate.
            val result = recordStore.create(
                recordTypeId = schema.transaction.recordTypeId,
                fieldValues = fieldValues,
                provenance = provenanceFor(txn.ingestMethod),
                now = txn.txnDate,
                guid = guid,
            )
            when (result) {
                is RecordStore.WriteResult.Success -> copied++
                is RecordStore.WriteResult.Failure -> anyFailure = true
            }
        }

        if (!anyFailure) prefs.edit().putBoolean(KEY_TRANSACTIONS_COMPLETED, true).apply()

        val trashed = reconcileSupersededProvisional(context)
        return Result(copied = copied, supersededTrashed = trashed, alreadyDone = false)
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
     * already use. */
    suspend fun runAll(context: Context) {
        runCatching { copyLedgerIfNeeded(context) }
    }
}
