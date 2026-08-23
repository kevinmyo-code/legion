package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for [AdvisorAdvice]. */
@Dao
interface AdvisorAdviceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(advice: AdvisorAdvice): Long

    /**
     * The last [limit] exchanges for [aspect], newest first - the window that rides each digest
     * (answer call 7). Callers read [AdvisorAdvice.gist]/[AdvisorAdvice.proposalJson] off these,
     * not [AdvisorAdvice.adviceText], to keep prompt cost bounded.
     */
    @Query("SELECT * FROM advisor_advice WHERE aspect = :aspect ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(aspect: String, limit: Int): List<AdvisorAdvice>

    /** One exchange by id, for the accept/reject screen path acting on its [AdvisorAdvice.proposalJson]. */
    @Query("SELECT * FROM advisor_advice WHERE id = :id")
    suspend fun pending(id: Long): AdvisorAdvice?

    /**
     * Every still-`pending` row for one [aspect], newest first (command-center ticket 11: the
     * hands path for `accept_proposal` - `service/LiveToolbox.kt`'s own dispatch reads a proposal
     * by [pending]'s single-id lookup because the live model always names a specific id; a driver
     * looking at a SCREEN has no id to name, so this is the list a screen needs instead. Additive
     * query, no schema change - matches this DAO's existing `recent`/`pending` shape.
     */
    @Query("SELECT * FROM advisor_advice WHERE aspect = :aspect AND outcome = 'pending' ORDER BY createdAt DESC")
    suspend fun pendingForAspect(aspect: String): List<AdvisorAdvice>

    /** Resolves a proposal's lifecycle in place - the one field [AdvisorAdvice] expects to change
     * post-insert, see its class doc. */
    @Query("UPDATE advisor_advice SET outcome = :outcome, resolvedAt = :resolvedAt WHERE id = :id")
    suspend fun markOutcome(id: Long, outcome: String, resolvedAt: Long)

    /** Atomically claims a `pending` row before executing its proposal, closing the
     * read-check-execute-write race `accept_proposal` otherwise has: two concurrent calls for the
     * SAME id (double-tap, or a model retry racing the original past `TOOL_TIMEOUT_MS`, whose
     * orphaned coroutine still completes - `service/LiveToolbox.kt`) can both read `outcome ==
     * "pending"` off a plain SELECT before either writes anything back. This `UPDATE ... WHERE
     * outcome = 'pending'` is the actual mutual-exclusion point: SQLite serialises writers, so only
     * ONE caller's UPDATE can match-and-flip the row from `pending`; the loser's WHERE clause finds
     * nothing to update and its rows-affected comes back 0. Returns the standard JDBC-style
     * rows-affected count - 1 means this caller now owns the row and must execute; 0 means someone
     * else already claimed it (or it was never pending), and this caller must NOT execute. [claimed]
     * is a transient state (`accepting`) that exists only between this call and [revertToPending] /
     * [markOutcome] settling it - never read as a terminal outcome anywhere else. */
    @Query("UPDATE advisor_advice SET outcome = :claimed, resolvedAt = :now WHERE id = :id AND outcome = 'pending'")
    suspend fun claimIfPending(id: Long, claimed: String, now: Long): Int

    /** Un-claims a row this caller [claimIfPending]-ed but could not settle as `accepted` - a
     * [com.kevin.legion.advisor.AdvisorProposalExecutor.ExecuteResult.Refused] or a verified
     * [com.kevin.legion.advisor.AdvisorProposalExecutor.ExecuteResult.WriteFailed]. Puts it back to
     * `pending` with [AdvisorAdvice.resolvedAt] cleared, exactly the shape it had before the claim,
     * so the SAME id remains retryable rather than being permanently stranded on the transient
     * `accepting` state or wrongly parked at `accepted` for a write that never landed. */
    @Query("UPDATE advisor_advice SET outcome = 'pending', resolvedAt = NULL WHERE id = :id")
    suspend fun revertToPending(id: Long)
}
