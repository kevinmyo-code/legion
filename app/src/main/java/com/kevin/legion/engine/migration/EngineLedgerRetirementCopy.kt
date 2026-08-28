package com.kevin.legion.engine.migration

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.ledger.LedgerRecordBridge

/**
 * Step 5 of the engine retirement sequence (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`):
 * the one-time, idempotent copier that reconciles the engine's `Transaction` records onto the
 * legacy `ledger_transactions` table BEFORE [com.kevin.legion.ledger.LedgerController]'s and
 * [com.kevin.legion.ledger.IngestPipeline]'s unconfigured read/write path is repointed off
 * [com.kevin.legion.engine.RecordStore] and onto [com.kevin.legion.data.local.LedgerTransactionDao].
 * Mirrors [EnginePlacesRetirementCopy]/[EnginePantryRetirementCopy]'s shape exactly - see those
 * objects' own class docs for the general reasoning ("runs the opposite direction from the wave
 * copiers", "deletes nothing"), repeated here only where ledger's answer differs.
 *
 * **Ticket 03's pairing note does NOT block this step.** That ticket says the engine retirement
 * lands "before or with" the `commit_statement` RPC move for ledger, never after - this is the
 * BEFORE half. Nothing here touches `commit_statement` or any Supabase call; it only repoints the
 * UNCONFIGURED path, the one every clone-and-run install with no Supabase project actually uses.
 * [com.kevin.legion.backend.LedgerReconcile] (the configured-transition upload tool) is untouched
 * and keeps reading the engine directly, exactly as [EnginePlacesRetirementCopy]/
 * [EnginePantryRetirementCopy] left `PlacesReconcile`/`PantryReconcile` alone.
 *
 * **Identity is by [com.kevin.legion.data.local.EngineRecord.guid], and here it works exactly like
 * pantry's, not places'.** [EngineDataMigrationWave3]'s forward copy keyed `guid = txn.syncId`
 * verbatim (see that object's own `copyLedgerIfNeeded`), and every row written the OTHER way -
 * [com.kevin.legion.ledger.IngestPipeline.commit]'s create calls,
 * [com.kevin.legion.ledger.LedgerController.logPendingTransaction]/`commitPlain` - passes
 * `guid = txn.syncId` too, where [com.kevin.legion.data.local.LedgerTransaction.syncId] mints its
 * own random UUID at construction (the entity's own default) before ever reaching the engine. So an
 * engine Transaction's guid is always, in both directions, the SAME string its legacy counterpart
 * carries as `syncId` - traced through [EngineDataMigrationWave3.copyLedgerIfNeeded],
 * [com.kevin.legion.ledger.IngestPipeline.commit] and [com.kevin.legion.ledger.LedgerController],
 * not assumed. `supabase/migrations/20260826000100_origin_guid.sql`'s "no natural key even in
 * principle" finding is about the REMOTE `ledger_transactions` table's upload idempotency (a
 * different problem, a different `origin_guid` column); it does not apply here, where the guid IS
 * the row's own identity, not a derived semantic key.
 *
 * **Reuses [LedgerRecordBridge.toTransaction] rather than re-reading the payload by hand.** That
 * function is already the one place engine-record-to-[com.kevin.legion.data.local.LedgerTransaction]
 * translation happens for every other reader of this record type
 * ([com.kevin.legion.ledger.LedgerController], [EngineDataMigrationWave3]) - a second, hand-rolled
 * field read here would be exactly the "three independent copies of one mapping" drift risk that
 * function's own class doc was extracted to prevent. Its `record.id -> LedgerTransaction.id`
 * mapping is overridden with `id = 0` below so Room autogenerates a fresh legacy row id rather than
 * trying to seat the copy at the engine's own (unrelated) numeric id.
 *
 * **Reconcile-and-repoint, never blind-switch (ticket 05's rule): this only ever fills gaps.** A
 * `syncId` already present in `ledger_transactions` (forward-copied by
 * [EngineDataMigrationWave3], or a previously-interrupted pass of this very copier) is left alone -
 * `ledger_transactions` wins ties, exactly as `places`/`pantry_receipts` do in the two prior steps.
 * There is no tombstone concept on this table (a hard `DELETE`, never a soft mark), so like pantry
 * the "already present" check alone is the whole guard.
 *
 * **Provenance is carried through, not asserted - unlike pantry's copier.** Pantry's `Receipt`
 * record type has exactly one possible provenance ([com.kevin.legion.data.local.RecordProvenance.LLM_RECONCILED],
 * hardcoded at every write site), so that copier could safely assert it. Ledger's `Transaction`
 * record type genuinely varies - `DETERMINISTIC`/`LLM_RECONCILED`/`UNRECONCILED`, per
 * [LedgerRecordBridge.ingestMethodFor]'s exhaustive map - and asserting a fixed value here would be
 * exactly the "row that arrives looking more (or less) verified than it was" failure CLAUDE.md
 * section 4 rule 7 exists to prevent. [LedgerRecordBridge.toTransaction] already reads
 * `record.provenance` through that map field-for-field, so nothing extra is needed here: the copy
 * is complete by construction, not by a special case for this field.
 *
 * **Deletes nothing.** The engine's Transaction records are read here and never trashed, updated,
 * or touched - ticket 15 is explicit that nothing is deleted until every aspect is repointed and
 * soaked.
 */
object EngineLedgerRetirementCopy {
    private const val PREFS = "engine_ledger_retirement"
    private const val KEY_COMPLETED = "ledger_repointed_v1"

    /** [copied] counts only rows actually written this call. [alreadyDone] is true only when the
     * SharedPreferences fast path skipped the pass entirely without even reading the engine. */
    data class Result(val copied: Int, val alreadyDone: Boolean)

    /**
     * Copies every active engine `Transaction` record whose guid has no row at all in
     * `ledger_transactions` into that table. Idempotent two ways, matching
     * [EnginePlacesRetirementCopy]/[EnginePantryRetirementCopy]'s own shape: the [KEY_COMPLETED]
     * flag short-circuits every call after the first successful pass, and even without it a re-run
     * is safe because the per-guid existence check simply finds nothing left to copy the second
     * time.
     */
    suspend fun copyIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) return Result(copied = 0, alreadyDone = true)

        val db = CarDatabase.getDatabase(context)
        val schema = LedgerAspectSeeder.ensureSeeded(context)

        // Every syncId `ledger_transactions` has ever seen - see this object's class doc for why an
        // existing syncId, not a semantic field, is the whole guard here.
        val existingSyncIds = db.ledgerTransactionDao().allSyncIds().toHashSet()

        val engineRecords = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId)

        var copied = 0
        for (record in engineRecords) {
            if (record.guid in existingSyncIds) continue // `ledger_transactions` wins ties - reconcile, never overwrite

            val txn = LedgerRecordBridge.toTransaction(record, schema.transaction.fieldIds).copy(id = 0)
            db.ledgerTransactionDao().insertAll(listOf(txn))

            existingSyncIds += record.guid // guards two engine records that somehow share a guid within one pass
            copied++
        }

        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        return Result(copied = copied, alreadyDone = false)
    }
}
