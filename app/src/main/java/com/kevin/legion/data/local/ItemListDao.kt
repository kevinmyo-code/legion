package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [ItemList]. */
@Dao
interface ItemListDao {
    @Insert
    suspend fun insert(list: ItemList): Long

    @Query("SELECT * FROM item_lists WHERE id = :id AND deleted = 0")
    suspend fun getById(id: Long): ItemList?

    @Query("SELECT * FROM item_lists WHERE deleted = 0 AND (archived = 0 OR :includeArchived = 1) ORDER BY sortOrder ASC, name ASC")
    suspend fun getAll(includeArchived: Boolean): List<ItemList>

    /**
     * Most-recently-used default (ticket 05). **Never resolves to an archived list** (ticket 11) -
     * the `archived = 0` clause is load-bearing, not a caller-side filter a future query could
     * forget to add.
     */
    @Query("SELECT * FROM item_lists WHERE deleted = 0 AND archived = 0 ORDER BY lastUsedAt DESC LIMIT 1")
    suspend fun mostRecentlyUsed(): ItemList?

    /** Fuzzy-name matching candidates (`notes/NotesLogic.kt` filters this list further). */
    @Query("SELECT * FROM item_lists WHERE deleted = 0")
    suspend fun getAllForMatch(): List<ItemList>

    // updatedAt is bumped alongside lastUsedAt so cross-device sync LWW (ticket 09, not wired
    // yet) would see a "used" touch as a fresh write, same discipline as CarTaskDao.markDone.
    @Query("UPDATE item_lists SET lastUsedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    /** Renames a list by hand - the single-list/editor screen's rename affordance (phase 2b). */
    @Query("UPDATE item_lists SET name = :name, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: Long, name: String, at: Long)

    @Query("UPDATE item_lists SET archived = 1, updatedAt = :at WHERE id = :id")
    suspend fun archive(id: Long, at: Long)

    @Query("UPDATE item_lists SET archived = 0, updatedAt = :at WHERE id = :id")
    suspend fun unarchive(id: Long, at: Long)

    // Soft delete (mirrors CarTaskDao.deleteById) - see CarTask's doc comment for why a hard
    // DELETE would be invisible to cross-device sync's snapshot.
    @Query("UPDATE item_lists SET deleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun deleteById(id: Long, at: Long)
}
