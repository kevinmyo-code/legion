package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CodeClearEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CodeClearEvent): Long

    /** Newest first - the UI (D7's union rule) and any future history surface both want this order. */
    @Query("SELECT * FROM code_clear_events WHERE vehicleId = :vehicleId ORDER BY timestamp DESC")
    suspend fun getAll(vehicleId: String): List<CodeClearEvent>

    /**
     * The single anchor D7's union rule filters against - the most recent row whose `outcome` is
     * `CLEARED` specifically. **Never RETURNED or UNVERIFIED** - D7's own text: "RETURNED and
     * UNVERIFIED clears do NOT filter anything." Only a genuine full clear ever moves the anchor
     * or earns the STORED CODES block's `CLEARED <date>` line.
     */
    @Query(
        "SELECT * FROM code_clear_events WHERE vehicleId = :vehicleId AND outcome = 'CLEARED' " +
            "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun getLatestCleared(vehicleId: String): CodeClearEvent?
}
