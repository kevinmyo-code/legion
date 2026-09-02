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
     *
     * live-sync ticket: `deleted = 0` added - a forgotten staple (see [deleteByName]'s own doc
     * comment) must not keep suggesting itself, the same "active reads exclude tombstones"
     * discipline every other synced table in this file follows.
     */
    @Query("SELECT * FROM grocery_staples WHERE deleted = 0 ORDER BY timesBought DESC, lastBoughtAt DESC LIMIT :limit")
    suspend fun topStaples(limit: Int): List<GroceryStaple>

    /**
     * Hard delete, kept as the UNCONFIGURED-install fallback only (no server copy exists to
     * tombstone) - [com.kevin.legion.backend.LastAspectsWriteThrough.forgetStaple]'s own fallback
     * branch, same shape as [LedgerConfigWriteThrough.deleteCategoryRulesBySubstring]'s bare-DAO
     * fallback. A CONFIGURED install calls [softDeleteByName] instead, so a forgotten staple can be
     * pushed as a tombstone rather than vanishing with no trace for the server to reconcile against.
     */
    @Query("DELETE FROM grocery_staples WHERE name = :name")
    suspend fun deleteByName(name: String)

    /** The configured-install "forget" path - tombstones rather than removes, so
     * [com.kevin.legion.backend.LastAspectsSync]'s pull can propagate the forget to the other
     * device instead of that device's own copy silently surviving forever. */
    @Query("UPDATE grocery_staples SET deleted = 1, updatedAtMs = :at WHERE name = :name")
    suspend fun softDeleteByName(name: String, at: Long)

    /** Every row regardless of [GroceryStaple.deleted] - [LastAspectsSync]/[LastAspectsBackfill]'s
     * merge/push read, same role [CategoryDao.getAllIncludingDeleted] plays for categories. */
    @Query("SELECT * FROM grocery_staples")
    suspend fun getAllIncludingDeleted(): List<GroceryStaple>

    /** [LastAspectsBackfill]'s own write-back, called immediately after a successful push - this
     * table has no autoincrement-id cursor to rely on instead (see that object's own class doc for
     * why), so unlike every cursor-backed table here, its "already pushed" check
     * (`row.serverId != null`) needs the local row updated THIS SAME RUN or an unchanged
     * `grocery_staples` row would look pending again on every subsequent backfill sweep - not just
     * until the next pull happens to fill it in, which [LedgerConfigBackfill]'s own cursor-backed
     * tables can safely wait for. */
    @Query("UPDATE grocery_staples SET serverId = :serverId WHERE name = :name")
    suspend fun setServerId(name: String, serverId: String)
}
