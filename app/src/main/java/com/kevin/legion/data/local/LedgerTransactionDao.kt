package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for [LedgerTransaction].
 */
@Dao
interface LedgerTransactionDao {
    @Insert
    suspend fun insertAll(transactions: List<LedgerTransaction>)

    /** Full history for one account, newest first. */
    @Query("SELECT * FROM ledger_transactions WHERE accountId = :accountId ORDER BY txnDate DESC")
    suspend fun getForAccount(accountId: String): List<LedgerTransaction>

    /**
     * Every ledger row, unconditionally, oldest-first by `id` (statement/insert order - see
     * [latestBalanceCents]'s doc comment for why `id` tracks file order). Added for
     * [com.kevin.legion.engine.migration.EngineDataMigrationWave3] - a plain additive `@Query`, no
     * schema/version change, same shape as `PantryReceiptDao.getAll()` added for Wave 2. Ordering by
     * `id` (not `txnDate`) is deliberate: it gives the migration a deterministic, repeatable pass
     * order across retries even though it has no bearing on correctness (the copier's own per-row
     * `guid` idempotency check does not care about order).
     */
    @Query("SELECT * FROM ledger_transactions ORDER BY id ASC")
    suspend fun getAll(): List<LedgerTransaction>

    /**
     * The exact set of [LedgerTransaction.syncId] values currently live in the legacy table -
     * [com.kevin.legion.engine.migration.EngineDataMigrationWave3]'s delete-reconciliation pass
     * reads this to find which previously-migrated [com.kevin.legion.data.local.RecordProvenance.UNRECONCILED]
     * engine records no longer have a legacy row behind them (deleted by
     * [deleteSupersededProvisional], [deletePendingById], or a replace-flow's
     * [deleteBySourceFileId]) and must be trashed to keep CLAUDE.md §4 rule 7's "can never outlive
     * or double-count against the verified row that supersedes it" guarantee true of the ENGINE
     * copy too, not just the legacy table it was copied from.
     */
    @Query("SELECT syncId FROM ledger_transactions")
    suspend fun allSyncIds(): List<String>

    /** Most recent transactions across all accounts, for `list_recent_transactions`. */
    @Query("SELECT * FROM ledger_transactions ORDER BY txnDate DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<LedgerTransaction>

    /**
     * The latest known stated balance for [accountId] (the transaction with the
     * most recent [LedgerTransaction.txnDate] that actually has one - Bank of
     * America's section layout doesn't print a per-transaction balance).
     *
     * **`id DESC` is the tie-break and it is load-bearing (fixed 2026-08-07,
     * found on Kevin's real device).** [LedgerTransaction.txnDate] is stored at
     * UTC midnight, so every transaction on one calendar day holds the SAME
     * value and `ORDER BY txnDate DESC` alone is a tie SQLite may break any way
     * it likes. It picked the FIRST of Kevin's four 6 August rows, reporting a
     * balance of 509.71 when the day actually closed at 440.68 - four rows
     * later, all four present and correct in the table.
     *
     * `id DESC` is right because the parsers insert in statement order and
     * every supported export is oldest-first (BofA's CSV opens with the
     * beginning-balance anchor row; see [com.kevin.legion.ledger.parsers.BofaCsvStatementParser]),
     * so a higher rowid within a day IS later in that day. **An import that
     * ever inserted newest-first would silently invert this**, so a new parser
     * must preserve file order or this breaks without any test noticing.
     */
    @Query(
        "SELECT balanceCents FROM ledger_transactions WHERE accountId = :accountId " +
            "AND balanceCents IS NOT NULL ORDER BY txnDate DESC, id DESC LIMIT 1"
    )
    suspend fun latestBalanceCents(accountId: String): Long?

    /** All known account ids, for a driver who doesn't name one. */
    @Query("SELECT DISTINCT accountId FROM ledger_transactions")
    suspend fun allAccountIds(): List<String>

    /**
     * Every [LedgerTransaction.accountId] that has ever appeared in [currency] - the "accounts
     * Kevin actually holds" set [com.kevin.legion.ledger.analyzeTransfers]'s own-account pass
     * reads (2026-08-13). Scoped to one currency, not [allAccountIds]'s whole-table set, because a
     * US-entity description referencing an account digit sequence must never be matched against an
     * SGD account it could never actually name - same "one entity, one currency" boundary
     * [com.kevin.legion.ledger.operatingExpenses] already applies to the rows themselves.
     */
    @Query("SELECT DISTINCT accountId FROM ledger_transactions WHERE currency = :currency")
    suspend fun accountIdsForCurrency(currency: LedgerCurrency): List<String>

    /**
     * [accountId]'s currency, read off its most recent transaction. An
     * account's currency doesn't vary row to row in practice (one account,
     * one bank, one currency), so there's no dedicated per-account table to
     * query instead - this is ticket 08's balances surface (resolution §5)
     * reading the one place currency already lives. Null only if [accountId]
     * has no rows at all, which shouldn't happen for an id that came from
     * [allAccountIds].
     */
    @Query("SELECT currency FROM ledger_transactions WHERE accountId = :accountId ORDER BY txnDate DESC LIMIT 1")
    suspend fun currencyForAccount(accountId: String): LedgerCurrency?

    /**
     * The candidate set ticket 04's dedup rewrite compares an incoming
     * statement against: every existing row for [accountId] whose date falls
     * within the incoming statement's own [minTxnDate]..[maxTxnDate] range,
     * inclusive. Deliberately NOT the comparison itself - it only narrows the
     * fetch. `LedgerDedup.resolveDedup` does the actual per-tuple counting, in
     * Kotlin, against this list. Replaces the old boolean `countMatching`
     * existence check, which collapsed two genuinely separate identical
     * purchases on the same day into one and silently dropped the second.
     */
    @Query(
        "SELECT * FROM ledger_transactions WHERE accountId = :accountId " +
            "AND txnDate BETWEEN :minTxnDate AND :maxTxnDate"
    )
    suspend fun getForAccountInRange(accountId: String, minTxnDate: Long, maxTxnDate: Long): List<LedgerTransaction>

    /**
     * The replace-flow's first step (ticket 03 amendment 2, wired in
     * [com.kevin.legion.ledger.IngestPipeline]): drop a file's own previously
     * committed rows before re-inserting its re-parsed replacement. Always
     * called inside the same Room transaction as
     * [IngestedFileDao.resetOverlapping], never on its own - see
     * `IngestPipeline.commit`'s doc comment for why both must happen
     * together.
     */
    @Query("DELETE FROM ledger_transactions WHERE sourceFileId = :sourceFileId")
    suspend fun deleteBySourceFileId(sourceFileId: String)

    /**
     * Every ledger row, unconditionally. Only ever called from
     * [com.kevin.legion.ledger.LedgerController.purgeAll], which pairs it with
     * the ingested-file ledger in one transaction - see that function for why
     * dropping only one of the two is worse than dropping neither.
     */
    @Query("DELETE FROM ledger_transactions")
    suspend fun deleteAll(): Int

    /**
     * Deletes provisional rows a reconciled file has now superseded. Suffix-matched
     * on the last 4 - see [com.kevin.legion.ledger.sameCard] and ticket 12 §0.
     */
    @Query(
        "DELETE FROM ledger_transactions WHERE ingestMethod = 'UNRECONCILED' " +
            "AND substr(accountId, -4) = substr(:accountId, -4) " +
            "AND txnDate BETWEEN :fromMs AND :toMs"
    )
    suspend fun deleteSupersededProvisional(accountId: String, fromMs: Long, toMs: Long): Int

    /**
     * The `txnDate` of the row [latestBalanceCents] read its value from - the
     * anchor ticket 12 §6's provisional delta is measured from. Any
     * [IngestMethod.UNRECONCILED] row dated on or before this date is already
     * reflected inside that printed balance; only rows strictly after it are
     * unposted movement the balance hasn't seen yet.
     *
     * **Must carry the same `id DESC` tie-break as [latestBalanceCents]** - see
     * that query's doc comment. These two have to agree on WHICH row is the
     * anchor: if one picked the 509.71 row and the other the 440.68 row, the
     * provisional delta would be measured from a date that does not belong to
     * the balance it is being added to, and the available figure would be
     * wrong in a way no test would catch.
     */
    @Query(
        "SELECT txnDate FROM ledger_transactions WHERE accountId = :accountId " +
            "AND balanceCents IS NOT NULL ORDER BY txnDate DESC, id DESC LIMIT 1"
    )
    suspend fun latestBalanceTxnDate(accountId: String): Long?

    /**
     * Sum of [IngestMethod.UNRECONCILED] rows for the same physical card
     * (suffix-matched on the last 4 - see [com.kevin.legion.ledger.sameCard]
     * and ticket 12 §0) dated strictly after [afterMs]: the mid-cycle
     * activity [accountId]'s latest printed balance (or the total absence of
     * one) hasn't accounted for. Null only when SQLite's `SUM` has nothing to
     * sum - the caller treats that as zero, never as "unknown".
     *
     * [currency] guard (review finding 3): `accountId` is free text from the
     * folder-mapping field, not a bank-assigned id, so a last-4 suffix match
     * ALONE can collide across two genuinely different accounts that happen
     * to share their trailing 4 characters (e.g. "BOFA-CHECKING" and
     * "DBS-CHECKING" both end "KING"). Without also requiring the same
     * currency, this query would sum a USD account's provisional rows into a
     * completely unrelated SGD account's delta. `accountId`'s own currency
     * doesn't vary row to row (one account, one bank, one currency - see
     * [currencyForAccount]'s doc comment), so the caller always has one to
     * pass.
     *
     * `AND pendingLoggedAt IS NULL` (ticket "voice-logged pending transactions"): a
     * [LedgerTransaction.pendingLoggedAt] row is ALSO tagged [IngestMethod.UNRECONCILED] - see
     * that field's doc comment for why - so without this guard it would double-count against
     * [pendingDeltaCents], which is the query that sums exactly those rows. This is the one
     * required edit to an existing query in that change: everything else about ticket 12's
     * provisional-row semantics here is unchanged.
     */
    @Query(
        "SELECT SUM(amountCents) FROM ledger_transactions WHERE ingestMethod = 'UNRECONCILED' " +
            "AND pendingLoggedAt IS NULL " +
            "AND substr(accountId, -4) = substr(:accountId, -4) " +
            "AND currency = :currency " +
            "AND txnDate > :afterMs"
    )
    suspend fun provisionalDeltaCentsAfter(accountId: String, currency: LedgerCurrency, afterMs: Long): Long?

    /**
     * Sum of every [LedgerTransaction.pendingLoggedAt]-tagged row for the same physical card
     * (suffix-matched on the last 4, [currency]-guarded - same collision reasoning as
     * [provisionalDeltaCentsAfter]'s own doc comment). Null only when SQLite's `SUM` has nothing
     * to sum - the caller treats that as zero, never as "unknown", matching every other delta
     * query here.
     *
     * **Deliberately no date filter.** A voice-logged pending charge is, by construction, not
     * reflected in ANY printed balance - there is no anchor date to filter after the way
     * [provisionalDeltaCentsAfter] filters after [LedgerTransactionDao.latestBalanceTxnDate].
     * Adding one would silently drop a pending row logged on the same calendar day as the last
     * statement row landed - a real defect in that other query's `>` boundary this one must not
     * inherit. Every pending row counts, always, until it is cleared or superseded.
     */
    @Query(
        "SELECT SUM(amountCents) FROM ledger_transactions WHERE pendingLoggedAt IS NOT NULL " +
            "AND substr(accountId, -4) = substr(:accountId, -4) " +
            "AND currency = :currency"
    )
    suspend fun pendingDeltaCents(accountId: String, currency: LedgerCurrency): Long?

    /** Every voice-logged pending row, most recently logged first - `list_pending_transactions`'s read. */
    @Query("SELECT * FROM ledger_transactions WHERE pendingLoggedAt IS NOT NULL ORDER BY pendingLoggedAt DESC")
    suspend fun pendingRows(): List<LedgerTransaction>

    /**
     * `clear_pending_transaction`'s delete. `AND pendingLoggedAt IS NOT NULL` is load-bearing, not
     * decorative: it makes it structurally impossible for this path to ever delete a reconciled
     * (or file-derived UNRECONCILED) row, even if a caller somehow passed the wrong id. Returns
     * the row count actually removed (0 or 1) so the caller can tell "deleted" from "id didn't
     * match a pending row".
     */
    @Query("DELETE FROM ledger_transactions WHERE id = :id AND pendingLoggedAt IS NOT NULL")
    suspend fun deletePendingById(id: Long): Int

    /**
     * True when [accountId] (suffix-matched on the last 4, same [currency] -
     * see [com.kevin.legion.ledger.sameCard] and ticket 12 §0) has at least
     * one row that is NOT [IngestMethod.UNRECONCILED] - i.e. this physical
     * card/account has ever been reconciled against a real statement,
     * regardless of whether that statement's format happens to print a
     * running balance.
     *
     * Review finding 5: "has a printed balance" and "has ever been
     * reconciled" are NOT the same question. Bank of America's card
     * statement PDF ([com.kevin.legion.ledger.parsers.BofaCardStatementParser])
     * produces [IngestMethod.DETERMINISTIC] rows that reconciled against the
     * statement's own printed TOTAL, but the parser never sets
     * [LedgerTransaction.balanceCents] on any of them - that format simply
     * doesn't print a running balance at all. [AccountBalanceRow]'s copy used
     * to branch on `balanceCents != null` alone, so an account whose only
     * statement is exactly this shape read as "no statement yet" forever,
     * even the month after its PDF was imported. This query is what lets the
     * copy ask the right question instead.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM ledger_transactions WHERE ingestMethod != 'UNRECONCILED' " +
            "AND substr(accountId, -4) = substr(:accountId, -4) AND currency = :currency)"
    )
    suspend fun hasReconciledRows(accountId: String, currency: LedgerCurrency): Boolean

    /**
     * Every row of [currency] whose `txnDate` falls in `[fromMs, toMs]`, ordered oldest-first with
     * `id` as the tie-break. Feeds [com.kevin.legion.ledger.LedgerController.budgetVsActual]:
     * called once with the wide "pairing window" range (the calendar month padded by
     * [com.kevin.legion.ledger.analyzeTransfers]'s `maxDaysApart` on each side), never once per month and
     * once per window - `com.kevin.legion.ledger.buildBudgetVsActual` derives the narrower in-period subset
     * from the same fetched list rather than this DAO being asked twice.
     */
    @Query(
        "SELECT * FROM ledger_transactions WHERE currency = :currency " +
            "AND txnDate BETWEEN :fromMs AND :toMs ORDER BY txnDate ASC, id ASC"
    )
    suspend fun getForCurrencyInRange(currency: LedgerCurrency, fromMs: Long, toMs: Long): List<LedgerTransaction>

    /** Earliest known `txnDate` for [currency], null with zero rows - [com.kevin.legion.ledger.LedgerController.monthsWithData]'s lower bound. */
    @Query("SELECT MIN(txnDate) FROM ledger_transactions WHERE currency = :currency")
    suspend fun earliestTxnDate(currency: LedgerCurrency): Long?

    /** Latest known `txnDate` for [currency], null with zero rows - [com.kevin.legion.ledger.LedgerController.monthsWithData]'s upper bound. */
    @Query("SELECT MAX(txnDate) FROM ledger_transactions WHERE currency = :currency")
    suspend fun latestTxnDate(currency: LedgerCurrency): Long?

    /**
     * Ticket 07 D16: applies one [com.kevin.legion.ledger.CategoryRule]-shaped substring match to
     * every row that currently has NO category at all. `category IS NULL` in the WHERE clause is
     * load-bearing twice over: it is what makes calling this repeatedly for every stored rule
     * idempotent (a row a prior rule already claimed is never re-matched by a later, looser one -
     * see [com.kevin.legion.data.local.CategoryRule]'s "earliest rule governs" doc comment), and
     * it is what keeps an AI-guessed row ([categoryPending] = true, [category] already non-null)
     * untouched by a plain rule sweep - a guess is only ever overwritten by
     * [com.kevin.legion.ledger.LedgerController.confirmCategoryGuess], never silently by whichever
     * rule sweep happens to run next.
     */
    @Query(
        "UPDATE ledger_transactions SET category = :category, categoryPending = 0 " +
            "WHERE category IS NULL AND UPPER(description) LIKE '%' || :substring || '%'"
    )
    suspend fun applyCategoryRule(substring: String, category: String): Int

    /**
     * Ticket 07 D17: assigns [category] as a PENDING guess to every currently-uncategorised row
     * whose UPPERCASED description contains [merchantKey] - the batch counterpart to
     * [applyCategoryRule], differing only in that [categoryPending] lands `1`, not `0`. Never
     * overwrites a row that already has a category (guessed or ruled), same `category IS NULL`
     * guard.
     */
    @Query(
        "UPDATE ledger_transactions SET category = :category, categoryPending = 1 " +
            "WHERE category IS NULL AND UPPER(description) LIKE '%' || :merchantKey || '%'"
    )
    suspend fun applyCategoryGuess(merchantKey: String, category: String): Int

    /**
     * Ticket 07 D18: confirming a guess clears [categoryPending] for every row this same
     * merchant key was guessed onto - not just the one row a driver happened to tap confirm on,
     * because the guess itself was made once per merchant key
     * ([com.kevin.legion.ledger.LedgerController.applyCategoryGuesses]), not once per row.
     * Requires `category = :category AND categoryPending = 1` so this can never confirm a
     * DIFFERENT category than the one actually guessed, and never touches an already-confirmed
     * row (a no-op update is cheap, but an unconditional one would silently "confirm" rows a
     * rule, not a guess, had assigned).
     */
    @Query(
        "UPDATE ledger_transactions SET categoryPending = 0 " +
            "WHERE categoryPending = 1 AND category = :category AND UPPER(description) LIKE '%' || :merchantKey || '%'"
    )
    suspend fun confirmCategoryGuess(merchantKey: String, category: String): Int

    /**
     * Ticket 07 D19: recategorising REWRITES HISTORY - this sets [LedgerTransaction.category] on
     * exactly one already-committed row, past months included, unconditionally (no `category IS
     * NULL` guard - correcting an existing category is the whole point). Always lands
     * [LedgerTransaction.categoryPending] `= false`: a hand-picked category is never "pending" -
     * a driver choosing directly is as confirmed a fact as this record has.
     */
    @Query("UPDATE ledger_transactions SET category = :category, categoryPending = 0 WHERE id = :id")
    suspend fun setCategoryConfirmed(id: Long, category: String)

    /**
     * Engine retirement step 5 (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`):
     * the per-row write [com.kevin.legion.ledger.LedgerController.updateCategoryOnRows] needs, now
     * that its writes go through this DAO rather than `RecordStore.update` against an already
     * Kotlin-filtered row list. Unlike [setCategoryConfirmed], [categoryPending] is a real
     * parameter here - the AI-guess path ([applyCategoryGuesses]) lands `true`, the rule/confirm/
     * hand-set paths land `false`, same three callers [setCategoryConfirmed] alone could never
     * serve. Returns the row count actually updated (0 or 1) so a caller iterating many ids can
     * tell a real write from an id that no longer exists.
     */
    @Query("UPDATE ledger_transactions SET category = :category, categoryPending = :categoryPending WHERE id = :id")
    suspend fun updateCategoryById(id: Long, category: String, categoryPending: Boolean): Int

    /**
     * Every FULL row still carrying no category at all - ticket 07 D17's candidate pool for
     * [com.kevin.legion.ledger.LedgerController.uncategorizedMerchants], widened 2026-08-13
     * (`.scratch/car-probe-transfers/`) from a bare `DISTINCT description` fetch to complete rows,
     * because [com.kevin.legion.ledger.analyzeTransfers] needs `accountId`/`amountCents`/`txnDate`
     * to classify a row as a transfer, not just its text. Callers are expected to have already run
     * [com.kevin.legion.ledger.LedgerController.applyCategoryRules] first, so what's left here is
     * genuinely rule-less, not just "not yet re-swept".
     */
    @Query("SELECT * FROM ledger_transactions WHERE category IS NULL")
    suspend fun uncategorizedTransactions(): List<LedgerTransaction>

    /**
     * Every ledger row, unconditionally. The ONLY caller is
     * [com.kevin.legion.ledger.LedgerController.uncategorizedMerchants], which needs a pairing-window
     * candidate set wide enough for [com.kevin.legion.ledger.analyzeTransfers] to find a transfer's
     * OTHER leg even when that leg already carries a category (a matched transfer must exclude BOTH
     * legs from the merchant-guessing pool, not only whichever leg is still uncategorised) or lies
     * outside any one [com.kevin.legion.ledger.LedgerController.budgetVsActual] month's own pairing
     * window. Not used anywhere else - a per-currency or per-month scoped fetch
     * ([getForCurrencyInRange]/[getForAccountInRange]) is always preferred when the caller can bound
     * the query the way that one can.
     */
    @Query("SELECT * FROM ledger_transactions")
    suspend fun allTransactions(): List<LedgerTransaction>

    /**
     * Every row still carrying an AI GUESS (D17/D18) rather than a confirmed category - the pool
     * [com.kevin.legion.ledger.LedgerController.pendingCategoryGuesses] hands the UI so a driver
     * can see and confirm/correct them. `categoryPending = 1` is the exact same flag
     * [applyCategoryGuess] sets and [confirmCategoryGuess]/[setCategoryForMerchant] clear - never
     * a separate notion of "pending" from the one those writes already maintain.
     */
    @Query("SELECT * FROM ledger_transactions WHERE categoryPending = 1 ORDER BY txnDate DESC")
    suspend fun categoryPendingRows(): List<LedgerTransaction>

    /**
     * `set_category`'s write (ticket B1, 2026-08-07): unconditionally sets [category] on every row
     * whose UPPERCASED description contains [merchantKey], confirmed ([LedgerTransaction.categoryPending]
     * `= 0`), regardless of whichever category (if any) the row previously held. This is D19's
     * "recategorising rewrites history" rule applied by merchant substring instead of by a single
     * row id - a voice correction names a MERCHANT, not one specific transaction, so it must reach
     * every row that merchant has ever produced, confirmed guess or not. Paired with a
     * [com.kevin.legion.data.local.CategoryRule] write in [com.kevin.legion.ledger.LedgerController.setCategory]
     * so the same correction also governs every future transaction from this merchant, not only the
     * ones already on file.
     */
    @Query(
        "UPDATE ledger_transactions SET category = :category, categoryPending = 0 " +
            "WHERE UPPER(description) LIKE '%' || :merchantKey || '%'"
    )
    suspend fun setCategoryForMerchant(merchantKey: String, category: String): Int

    /**
     * How many DISTINCT descriptions [merchantKey] would reach (audit fix,
     * 2026-08-07). Called before [setCategoryForMerchant] so the scope of an
     * unconditional, history-rewriting update is measured rather than
     * discovered.
     *
     * The hazard is specific: `merchantKey` is free text spoken at a
     * half-duplex voice pipe, matched as a bare `LIKE '%...%'` substring. A
     * garbled or genuinely short key ("AT", "CO") is a substring of a great
     * many real bank descriptions, and the update rewrites every match
     * unconditionally per D19. Row count alone does not reveal that - forty
     * rows from one merchant is normal and forty rows from twelve merchants is
     * a runaway - so the DISTINCT description count is what the caller has to
     * see.
     */
    @Query(
        "SELECT COUNT(DISTINCT description) FROM ledger_transactions " +
            "WHERE UPPER(description) LIKE '%' || :merchantKey || '%'"
    )
    suspend fun countDistinctDescriptionsMatching(merchantKey: String): Int

    /**
     * Row count [merchantKey] would touch if applied via [setCategoryForMerchant] - the "how many
     * TRANSACTIONS" figure the drill-down's hand-recategorise panel
     * ([com.kevin.legion.ui.ledger.CategoryDrilldownScreen], 2026-08-07) previews BEFORE
     * committing, distinct from [countDistinctDescriptionsMatching]'s per-MERCHANT blast-radius
     * count that gates the write itself - one merchant description repeated across many statements
     * (`PETCO 5421 CYPRESS TX` charged every month) is ONE distinct description but many rows, and
     * the driver asked "how many transactions", not "how many merchants". Read-only, no side
     * effect - safe to call on every keystroke of the editable key field.
     */
    @Query(
        "SELECT COUNT(*) FROM ledger_transactions " +
            "WHERE UPPER(description) LIKE '%' || :merchantKey || '%'"
    )
    suspend fun countMatching(merchantKey: String): Int
}
