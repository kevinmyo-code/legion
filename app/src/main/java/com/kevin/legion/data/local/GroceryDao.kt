package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for the current grocery trip ([GroceryItem]). */
@Dao
interface GroceryItemDao {
    @Insert
    suspend fun insert(item: GroceryItem): Long

    @Query("SELECT * FROM grocery_items ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<GroceryItem>

    @Query("SELECT * FROM grocery_items WHERE id = :id")
    suspend fun getById(id: Long): GroceryItem?

    @Query("SELECT COUNT(*) FROM grocery_items")
    suspend fun count(): Int

    @Query("UPDATE grocery_items SET done = 1, doneAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun markDone(id: Long, at: Long)

    @Query("UPDATE grocery_items SET done = 0, doneAt = NULL, updatedAt = :at WHERE id = :id")
    suspend fun markUndone(id: Long, at: Long)

    @Query("UPDATE grocery_items SET text = :text, updatedAt = :at WHERE id = :id")
    suspend fun updateText(id: Long, text: String, at: Long)

    /** Hard delete, not a tombstone - see [GroceryItem]'s doc comment for why this table has none. */
    @Query("DELETE FROM grocery_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Tears the whole trip down. The other half of "made once, torn down once complete". */
    @Query("DELETE FROM grocery_items")
    suspend fun clearAll()
}

/** Data Access Object for the staples memory ([GroceryStaple]). */
@Dao
interface GroceryStapleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(staple: GroceryStaple)

    @Query("SELECT * FROM grocery_staples WHERE name = :name")
    suspend fun getByName(name: String): GroceryStaple?

    /**
     * Suggestions, most-bought first, most-recent as the tiebreak.
     *
     * Frequency alone would pin a thing bought weekly for a year above something bought on the last
     * three trips running, long after the habit changed; recency alone would surface a one-off. The
     * pair reads as "what you usually buy, favouring what you have bought lately".
     */
    @Query("SELECT * FROM grocery_staples ORDER BY timesBought DESC, lastBoughtAt DESC LIMIT :limit")
    suspend fun topStaples(limit: Int): List<GroceryStaple>

    @Query("DELETE FROM grocery_staples WHERE name = :name")
    suspend fun deleteByName(name: String)
}
