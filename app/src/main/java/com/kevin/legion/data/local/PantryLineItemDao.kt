package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PantryLineItemDao {
    @Insert
    suspend fun insertAll(items: List<PantryLineItem>)

    @Query("SELECT * FROM pantry_line_items WHERE receiptId = :receiptId")
    suspend fun getForReceipt(receiptId: Long): List<PantryLineItem>

    /**
     * EVERY line item, unlimited, ordered by id - the Room-replica read PantryController uses when
     * Supabase is configured (backend-erp Phase 4, pantry aspect). A plain additive `@Query`, same
     * shape as [PantryReceiptDao.getAll]'s own doc comment on why no schema/version change is
     * needed for a read that exposes only existing columns.
     */
    @Query("SELECT * FROM pantry_line_items ORDER BY id ASC")
    suspend fun getAll(): List<PantryLineItem>

    /** Wipes the replica clean before [com.kevin.legion.backend.PantryReconcile] refills it from the
     * server's own active set - there is no natural key to upsert against (unlike
     * [com.kevin.legion.data.local.PlaceDao.upsert]'s label), so a full clear-and-refill is what
     * makes a re-run idempotent rather than duplicating every row. Never called from the regular
     * read/write path - only the one-time reconcile job. */
    @Query("DELETE FROM pantry_line_items")
    suspend fun deleteAllForReplicaRefresh()

    /** Every [PantryLineItem.syncId] this table has ever seen - the existence check
     * [com.kevin.legion.engine.migration.EnginePantryRetirementCopy] needs so a line item already
     * landed here is never duplicated by copying its engine record back in. Same "no natural key
     * even in principle, so guid is the only thing to key on" reasoning as
     * `supabase/migrations/20260826000100_origin_guid.sql`'s `receipt_line_items` comment - two
     * identical lines on one receipt are legitimate, so nothing besides the syncId itself could
     * tell them apart. */
    @Query("SELECT syncId FROM pantry_line_items")
    suspend fun getAllSyncIds(): List<String>

    /** Most recent items across all receipts, newest receipt first, for `list_recent_groceries`. */
    @Query(
        "SELECT pantry_line_items.* FROM pantry_line_items " +
            "JOIN pantry_receipts ON pantry_line_items.receiptId = pantry_receipts.id " +
            "ORDER BY pantry_receipts.purchaseDate DESC LIMIT :limit"
    )
    suspend fun getRecent(limit: Int): List<PantryLineItem>

    /**
     * Same rows as [getRecent], each paired with its OWN receipt's [LedgerCurrency] (2026-08-07
     * currency audit). [PantryLineItem] itself carries no currency column - a household mixing
     * SGD and USD groceries (same shape the ledger already handles, CLAUDE.md §4 rule "never
     * combine currencies without saying so") needs every item tagged with the currency ITS OWN
     * receipt was printed in, never the device's, never assumed.
     */
    @Query(
        "SELECT pantry_line_items.*, pantry_receipts.currency AS currency FROM pantry_line_items " +
            "JOIN pantry_receipts ON pantry_line_items.receiptId = pantry_receipts.id " +
            "ORDER BY pantry_receipts.purchaseDate DESC LIMIT :limit"
    )
    suspend fun getRecentWithCurrency(limit: Int): List<PantryLineItemWithCurrency>
}

/** [PantryLineItemDao.getRecentWithCurrency]'s row shape - see that query's doc comment. */
data class PantryLineItemWithCurrency(
    @Embedded val item: PantryLineItem,
    val currency: LedgerCurrency,
)
