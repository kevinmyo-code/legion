package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** SGD/USD two-country household ledger, per `.claude/plans/wiggly-beaming-quasar.md`. */
enum class LedgerCurrency { SGD, USD }

/**
 * How this row was ingested. Never silently indistinguishable from a
 * deterministically-parsed row - the same "anchored to falsifiable reality"
 * thesis CLAUDE.md §9.1 states for the assistant generally applies to its
 * financial data too. [LLM_RECONCILED] rows still passed the same
 * balance-continuity reconciliation gate as [DETERMINISTIC] ones before being
 * written; the tag is for later audit, not a trust discount.
 */
enum class IngestMethod { DETERMINISTIC, LLM_RECONCILED }

/**
 * One transaction line exactly as printed on a source bank statement. Ported
 * from Project Andromeda's `duo_ledger.bronze.model.BronzeTransaction`
 * (Python, `~/PycharmProjects/Andromeda`) - same fields, same exactness
 * discipline.
 *
 * [amountCents]/[balanceCents] are `Long` minor-units, not `Double` -
 * deliberate deviation from [BuildEntry]/[ServiceRecord]'s `Double` cost
 * fields. Those are a personal spend log; this is a ledger whose entire
 * reconciliation gate depends on exact equality checks
 * (`actualTotal == statedTotal`), which `Double` breaks via binary rounding.
 * Mirrors Python's `Decimal` exactness in `bronze/model.py`/`_money.py`.
 *
 * Global, not per-vehicle: a household ledger has nothing to do with which
 * car is active (unlike [BuildEntry]/[ServiceRecord], which are per-vehicle).
 *
 * [amountCents] is signed: negative for a withdrawal/debit, positive for a
 * deposit/credit. [balanceCents] is the statement's own stated running
 * balance after this transaction, when the source format prints one - null
 * when the source format doesn't (e.g. Bank of America's section layout).
 */
@Entity(tableName = "ledger_transactions")
data class LedgerTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceFile: String,
    val accountId: String,
    val currency: LedgerCurrency,
    val txnDate: Long, // epoch millis, matches ServiceRecord/BuildEntry convention
    val description: String,
    val amountCents: Long,
    val balanceCents: Long? = null,
    val lineRef: String,
    val ingestMethod: IngestMethod,
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    val syncId: String = java.util.UUID.randomUUID().toString(),
    /**
     * Which [IngestedFile] produced this row, when it came from the folder-scan
     * or single-pick pipeline (ticket 03). Nullable and deliberately has NO
     * `@ForeignKey`: `onDelete = CASCADE` would let deleting a file record
     * silently delete committed financial rows. Any rollback of a file's rows
     * must be an explicit, visible `DELETE ... WHERE sourceFileId = :id` in
     * code, never an implicit cascade. Also null for anything imported before
     * this column existed, or through a path that predates the scan pipeline.
     */
    val sourceFileId: String? = null,
)
