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
    @Query("SELECT currency, COALESCE(SUM(totalCents), 0) AS totalCents FROM pantry_receipts GROUP BY currency")
    suspend fun totalSpendCentsByCurrency(): List<PantryCurrencyTotal>

    /**
     * `(purchaseDate, totalCents, currency)` for EVERY receipt (quant-viz ticket 07) - the monthly
     * spend chart groups the driver's WHOLE history, not [getRecent]'s capped list, so this is a
     * second, lighter read rather than lifting that cap (ticket 07's own instruction: "do NOT lift
     * the receipt-list limit itself"). No line items, no photo path - just what the chart needs.
     */
    @Query("SELECT purchaseDate, totalCents, currency FROM pantry_receipts")
    suspend fun getAllForCharts(): List<PantryReceiptSummary>
}

/** [PantryReceiptDao.totalSpendCentsByCurrency]'s row shape. */
data class PantryCurrencyTotal(val currency: LedgerCurrency, val totalCents: Long)

/** [PantryReceiptDao.getAllForCharts]'s row shape - one receipt's date/total/currency, nothing else. */
data class PantryReceiptSummary(val purchaseDate: Long, val totalCents: Long, val currency: LedgerCurrency)
