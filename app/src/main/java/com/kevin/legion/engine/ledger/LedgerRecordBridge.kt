package com.kevin.legion.engine.ledger

import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.PayloadCodec
import org.json.JSONObject

/**
 * Cutover 3 (`docs/architecture/cutover3-2026-08-24.md`). The ONE place [LedgerTransaction] <->
 * [EngineRecord] translation happens, so [com.kevin.legion.ledger.IngestPipeline] (the write path),
 * [com.kevin.legion.ledger.LedgerController] (every read, plus `logPendingTransaction`/
 * `clearPendingTransaction`), and [com.kevin.legion.engine.migration.EngineDataMigrationWave3] (the
 * catch-up copier) all read and write a `Transaction` engine record the exact same way. Reuses
 * `docs/architecture/wave3-carve-2026-08-23.md`'s field mapping verbatim - not a second mapping.
 *
 * Before this file existed, the field map and the `ingestMethod`<->`RecordProvenance` map each
 * lived once, inline, inside [EngineDataMigrationWave3] alone (the only writer at the time). Cutover
 * adds a second and third writer of the SAME shape ([com.kevin.legion.ledger.IngestPipeline]'s commit
 * path, [com.kevin.legion.ledger.LedgerController]'s pending-log write) and a first REAL reader
 * ([com.kevin.legion.ledger.LedgerController]'s every other function) - three independent copies of
 * one mapping is exactly the drift risk CLAUDE.md's lessons ledger warns about, so it is extracted
 * here once instead.
 */
object LedgerRecordBridge {

    /**
     * [IngestMethod] -> [RecordProvenance], one-for-one, exhaustive, no `else` - see
     * [EngineDataMigrationWave3]'s own class doc for why a fourth [IngestMethod] value can never
     * reach this function (Room's enum column handling throws before a [LedgerTransaction] Kotlin
     * instance carrying one could ever exist).
     */
    fun provenanceFor(ingestMethod: IngestMethod): RecordProvenance = when (ingestMethod) {
        IngestMethod.DETERMINISTIC -> RecordProvenance.DETERMINISTIC
        IngestMethod.LLM_RECONCILED -> RecordProvenance.LLM_RECONCILED
        IngestMethod.UNRECONCILED -> RecordProvenance.UNRECONCILED
    }

    /**
     * The reverse of [provenanceFor], for reading an [EngineRecord] back as a [LedgerTransaction].
     * [RecordProvenance.USER] is not `IngestMethod`-representable - it can never actually arrive
     * here (every writer of the Ledger aspect's `Transaction` record type uses [provenanceFor],
     * which never produces it), so this defensively reads it as [IngestMethod.UNRECONCILED] rather
     * than crashing a read over a shape that should be structurally impossible - "never assert an
     * outcome/fact stronger than what was observed" applied to a read path, not just a spoken claim.
     */
    fun ingestMethodFor(provenance: RecordProvenance): IngestMethod = when (provenance) {
        RecordProvenance.DETERMINISTIC -> IngestMethod.DETERMINISTIC
        RecordProvenance.LLM_RECONCILED -> IngestMethod.LLM_RECONCILED
        RecordProvenance.UNRECONCILED -> IngestMethod.UNRECONCILED
        RecordProvenance.USER -> IngestMethod.UNRECONCILED
    }

    /** [LedgerAspectSeeder]'s field mapping, applied to one [LedgerTransaction] - the exact map
     * [RecordStore.create] needs. Used by both [com.kevin.legion.ledger.IngestPipeline]'s commit
     * (a fresh statement row) and [com.kevin.legion.ledger.LedgerController.logPendingTransaction]
     * (a hand/voice-authored pending row) - same shape, same field ids, never duplicated. */
    fun fieldValuesFor(txn: LedgerTransaction, fieldIds: Map<String, Long>): Map<Long, Any?> = mapOf(
        fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE) to txn.sourceFile,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_ACCOUNT_ID) to txn.accountId,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_CURRENCY) to txn.currency.name,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_TXN_DATE) to txn.txnDate,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_DESCRIPTION) to txn.description,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_AMOUNT) to txn.amountCents,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_BALANCE) to txn.balanceCents,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_LINE_REF) to txn.lineRef,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE_ID) to txn.sourceFileId,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY) to txn.category,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY_PENDING) to txn.categoryPending,
        fieldIds.getValue(LedgerAspectSeeder.FIELD_PENDING_LOGGED_AT) to txn.pendingLoggedAt,
    )

    /**
     * [EngineRecord] -> [LedgerTransaction], the read-side mirror of [fieldValuesFor]. `record.id`
     * becomes [LedgerTransaction.id] - post-cutover, "the id" IS the engine record id everywhere;
     * every caller that used to compare/store a `LedgerTransaction.id` (voice tools, UI, D19's
     * `recategorize(transactionId, ...)`) keeps working unchanged because this is the only id that
     * exists now. `record.guid` becomes [LedgerTransaction.syncId], matching every prior wave's
     * `syncId`-is-`guid` convention.
     */
    fun toTransaction(record: EngineRecord, fieldIds: Map<String, Long>): LedgerTransaction {
        val payload = JSONObject(record.payload)
        fun s(name: String) = PayloadCodec.readString(payload, fieldIds.getValue(name))
        fun l(name: String) = PayloadCodec.readLong(payload, fieldIds.getValue(name))
        fun b(name: String) = PayloadCodec.readBoolean(payload, fieldIds.getValue(name))
        val currency = LedgerCurrency.entries.firstOrNull { it.name == s(LedgerAspectSeeder.FIELD_CURRENCY) }
            ?: LedgerCurrency.USD
        return LedgerTransaction(
            id = record.id,
            sourceFile = s(LedgerAspectSeeder.FIELD_SOURCE_FILE).orEmpty(),
            accountId = s(LedgerAspectSeeder.FIELD_ACCOUNT_ID).orEmpty(),
            currency = currency,
            txnDate = l(LedgerAspectSeeder.FIELD_TXN_DATE) ?: record.createdAt,
            description = s(LedgerAspectSeeder.FIELD_DESCRIPTION).orEmpty(),
            amountCents = l(LedgerAspectSeeder.FIELD_AMOUNT) ?: 0L,
            balanceCents = l(LedgerAspectSeeder.FIELD_BALANCE),
            lineRef = s(LedgerAspectSeeder.FIELD_LINE_REF).orEmpty(),
            ingestMethod = ingestMethodFor(record.provenance),
            syncId = record.guid,
            sourceFileId = s(LedgerAspectSeeder.FIELD_SOURCE_FILE_ID),
            category = s(LedgerAspectSeeder.FIELD_CATEGORY),
            categoryPending = b(LedgerAspectSeeder.FIELD_CATEGORY_PENDING),
            pendingLoggedAt = l(LedgerAspectSeeder.FIELD_PENDING_LOGGED_AT),
        )
    }
}
