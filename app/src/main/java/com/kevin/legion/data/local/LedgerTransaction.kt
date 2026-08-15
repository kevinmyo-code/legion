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
 *
 * [UNRECONCILED] (ticket 12, CLAUDE.md §4 rule 7): extracted deterministically
 * from a source that states no anchor to check against at all - Bank of
 * America's mid-cycle card CSV export prints no balance and no total, so
 * there is nothing for a row from it to reconcile against, ever, not even in
 * principle. Rows of this kind are **never asserted as fact** - they are
 * costed as "the best currently-known figure, unverified" every place they
 * render, and they are transient by construction: [BofaCardCsvStatementParser]
 * produces nothing else, and any reconciled statement that later covers the
 * same dates deletes them (`LedgerTransactionDao.deleteSupersededProvisional`).
 * This is narrower than [LLM_RECONCILED] in a way that matters: an
 * [LLM_RECONCILED] row passed the same gate a [DETERMINISTIC] one did, only
 * the extraction method differed. An [UNRECONCILED] row never faced a gate at
 * all, because none existed to face.
 */
enum class IngestMethod { DETERMINISTIC, LLM_RECONCILED, UNRECONCILED }

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
    /**
     * Ticket 07 (`.scratch/legion-shape/issues/07-categorisation.md`, D14-D19). Null means
     * uncategorised - D11's own loud bucket, never a category named "Uncategorised" living in
     * [Category]. Set by [com.kevin.legion.ledger.matchCategory] (a [CategoryRule] matched),
     * [com.kevin.legion.ledger.CategoryAgent] (an AI guess - always paired with [categoryPending]
     * `= true`), or a direct hand-edit via [com.kevin.legion.ledger.LedgerController.recategorize].
     * Always one of [Category.name]'s fixed spellings when non-null (D14) - nothing writes an
     * arbitrary string here.
     */
    val category: String? = null,
    /**
     * True only for a row whose [category] came from an unconfirmed AI guess
     * ([com.kevin.legion.ledger.CategoryAgent]) - ticket 07 D18/§"A category is a REPORTED fact":
     * a guess is a reported claim about this row, distinct from [category] itself being null.
     * [com.kevin.legion.ledger.LedgerController.confirmCategoryGuess] is the only thing that
     * flips this back to false, and it does so by creating a [CategoryRule] at the same time
     * (D18 - "the whole stability answer": the same merchant is never guessed twice). Meaningless
     * when [category] is null - a row with no category has nothing pending to confirm.
     */
    val categoryPending: Boolean = false,
    /**
     * Non-null means "the driver told me about this by voice; no file has ever mentioned it."
     * Null is every existing row shape, unchanged - a document-derived row of any provenance.
     *
     * **Deliberately separate from [ingestMethod] == [IngestMethod.UNRECONCILED]**, even though a
     * pending-logged row is always tagged that way too (see below). An [IngestMethod.UNRECONCILED]
     * row from Bank of America's mid-cycle card CSV export (ticket 12) is a row a FILE stated but
     * could not anchor against a printed total - some real document exists, it just proves nothing
     * on its own. A [pendingLoggedAt] row is one no file has mentioned at all; it exists purely
     * because a driver said so out loud. These are summed by two different DAO queries
     * ([LedgerTransactionDao.provisionalDeltaCentsAfter] excludes anything with [pendingLoggedAt]
     * set, [LedgerTransactionDao.pendingDeltaCents] is the mirror that ONLY sums rows with it set)
     * specifically so a voice-logged charge is never double-counted against a CSV-derived one, and
     * they must be WORDED differently for the same reason [com.kevin.legion.ledger.LedgerBudget]
     * deliberately keeps `hasProvisionalRows` and `hasPendingCategoryGuesses` as two separate
     * booleans rather than collapsing them into one
     * "something's unconfirmed here" flag - two claims that are true for different reasons must
     * stay two claims, or a driver who fixes one cause has no way to tell the warning is stale.
     *
     * Still tagged [IngestMethod.UNRECONCILED] (CLAUDE.md §4 rule 7's own vocabulary already covers
     * "extracted deterministically from a source that states no anchor to check against" - a
     * driver's own spoken word is exactly that, the most literal case of "no document to reconcile
     * against" there is). No new [IngestMethod] constant exists for this, on purpose: adding one
     * would fork every `ingestMethod != UNRECONCILED` / `== UNRECONCILED` check already written
     * against ticket 12's provisional rows into a three-way branch for no benefit, when
     * [pendingLoggedAt] already answers the one question that actually needs answering ("did a
     * file say this, or did a person").
     */
    val pendingLoggedAt: Long? = null,
)
