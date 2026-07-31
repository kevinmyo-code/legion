package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PantryLineItemDao {
    @Insert
    suspend fun insertAll(items: List<PantryLineItem>)

    @Query("SELECT * FROM pantry_line_items WHERE receiptId = :receiptId")
    suspend fun getForReceipt(receiptId: Long): List<PantryLineItem>

    /** Most recent items across all receipts, newest receipt first, for `list_recent_groceries`. */
    @Query(
        "SELECT pantry_line_items.* FROM pantry_line_items " +
            "JOIN pantry_receipts ON pantry_line_items.receiptId = pantry_receipts.id " +
            "ORDER BY pantry_receipts.purchaseDate DESC LIMIT :limit"
    )
    suspend fun getRecent(limit: Int): List<PantryLineItem>
}
