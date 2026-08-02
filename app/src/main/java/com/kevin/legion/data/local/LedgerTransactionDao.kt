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
}
