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

    /** Most recent transactions across all accounts, for `list_recent_transactions`. */
    @Query("SELECT * FROM ledger_transactions ORDER BY txnDate DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<LedgerTransaction>

    /**
     * The latest known stated balance for [accountId] (the transaction with the
     * most recent [LedgerTransaction.txnDate] that actually has one - Bank of
     * America's section layout doesn't print a per-transaction balance).
     */
    @Query(
        "SELECT balanceCents FROM ledger_transactions WHERE accountId = :accountId " +
            "AND balanceCents IS NOT NULL ORDER BY txnDate DESC LIMIT 1"
    )
    suspend fun latestBalanceCents(accountId: String): Long?

    /** All known account ids, for a driver who doesn't name one. */
    @Query("SELECT DISTINCT accountId FROM ledger_transactions")
    suspend fun allAccountIds(): List<String>

    /**
     * Whether a transaction matching this real-world content already exists -
     * the dedup check re-importing an overlapping statement needs. Deliberately
     * NOT keyed on [LedgerTransaction.lineRef]/[LedgerTransaction.sourceFile]:
     * two different exports of the same underlying transaction (a monthly PDF
     * vs. a year-to-date PDF covering the same date) have different filenames
     * and line text, but are still the same transaction and must not double-count.
     */
    @Query(
        "SELECT COUNT(*) FROM ledger_transactions WHERE accountId = :accountId " +
            "AND txnDate = :txnDate AND amountCents = :amountCents AND description = :description"
    )
    suspend fun countMatching(accountId: String, txnDate: Long, amountCents: Long, description: String): Int
}
