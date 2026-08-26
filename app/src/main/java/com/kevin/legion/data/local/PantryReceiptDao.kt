package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PantryReceiptDao {
    /** Returns the new row's id, so line items can be inserted with the right `receiptId`. */
    @Insert
    suspend fun insert(receipt: PantryReceipt): Long

    @Query("SELECT * FROM pantry_receipts ORDER BY purchaseDate DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PantryReceipt>

    /**
     * EVERY receipt, unlimited, ordered by id (insertion order) rather than [purchaseDate] -
     * `engine/migration/EngineDataMigrationWave2.kt`'s copier needs a stable, complete pass over
     * the whole legacy table (there is no `deleted` column to filter on - see
     * `docs/architecture/wave2-carve-2026-08-23.md`'s finding that every row here already passed
     * the reconciliation gate), not [getRecent]'s capped, purchase-date-ordered read. A plain
     * additive `@Query` - no schema/version change, same columns [getRecent]/[getAllForCharts]
     * already expose.
     */
    @Query("SELECT * FROM pantry_receipts ORDER BY id ASC")
    suspend fun getAll(): List<PantryReceipt>

    @Query("SELECT COALESCE(SUM(totalCents), 0) FROM pantry_receipts")
    suspend fun totalSpendCents(): Long

    /**
     * Total spend PER currency (2026-08-07 currency audit) - the [totalSpendCents] query above
     * silently summed SGD and USD receipts into one bare cents figure, the exact "combine
     * currencies without saying so" failure CLAUDE.md §4 already refuses for the ledger
     * (`LedgerController.accountBalances`'s per-currency [AccountBalance] rows, never one
     * combined headline). `get_grocery_spend` reads this instead; [totalSpendCents] itself is
     * left in place only because nothing besides this new query needs to change it, not because
     * it's still safe to call on its own.
     */
    @Query(
        "SELECT currency, COALESCE(SUM(totalCents), 0) AS totalCents, " +
            "MAX(CASE WHEN unaccountedCents IS NOT NULL THEN 1 ELSE 0 END) AS hasUnreconciled " +
            "FROM pantry_receipts GROUP BY currency",
    )
    suspend fun totalSpendCentsByCurrency(): List<PantryCurrencyTotal>

    /**
     * `(purchaseDate, totalCents, currency)` for EVERY receipt (quant-viz ticket 07) - the monthly
     * spend chart groups the driver's WHOLE history, not [getRecent]'s capped list, so this is a
     * second, lighter read rather than lifting that cap (ticket 07's own instruction: "do NOT lift
     * the receipt-list limit itself"). No line items, no photo path - just what the chart needs.
     */
    @Query("SELECT purchaseDate, totalCents, currency FROM pantry_receipts")
    suspend fun getAllForCharts(): List<PantryReceiptSummary>

    /** Wipes the replica clean before [com.kevin.legion.backend.PantryReconcile] refills it - see
     * [PantryLineItemDao.deleteAllForReplicaRefresh]'s doc comment for why a full clear-and-refill,
     * not an upsert, is what makes the migration job idempotent here. Never called from the regular
     * read/write path. */
    @Query("DELETE FROM pantry_receipts")
    suspend fun deleteAllForReplicaRefresh()
}

/**
 * [PantryReceiptDao.totalSpendCentsByCurrency]'s row shape.
 *
 * [hasUnreconciled] is computed by [com.kevin.legion.pantry.PantryController.totalSpendCentsByCurrency]
 * in Kotlin, not by [PantryReceiptDao.totalSpendCentsByCurrency]'s own SQL (which this repo does
 * not call from production - the controller re-derives every total in memory so the same code path
 * covers both the Room replica and the unconfigured engine read). `true` means at least one receipt
 * folded into [totalCents] carries a non-null [PantryReceipt.unaccountedCents] - CLAUDE.md section 4
 * rule 7 condition 3's "every surface that renders one says so in words" applies to this AGGREGATE
 * exactly as much as to the receipt itself: a total that silently mixes verified and unverified
 * money is the failure the rule forbids, so every renderer of this type must say so when this is
 * true, never render the figure as if every receipt behind it settled cleanly.
 */
data class PantryCurrencyTotal(val currency: LedgerCurrency, val totalCents: Long, val hasUnreconciled: Boolean = false)

/** [PantryReceiptDao.getAllForCharts]'s row shape - one receipt's date/total/currency, nothing else. */
data class PantryReceiptSummary(val purchaseDate: Long, val totalCents: Long, val currency: LedgerCurrency)
