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

    @Query("SELECT COALESCE(SUM(totalCents), 0) FROM pantry_receipts")
    suspend fun totalSpendCents(): Long
}
