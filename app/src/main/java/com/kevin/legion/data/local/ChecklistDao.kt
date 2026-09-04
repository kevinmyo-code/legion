package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [Checklist]. */
@Dao
interface ChecklistDao {
    @Insert
    suspend fun insert(checklist: Checklist): Long

    @Query("SELECT * FROM checklists WHERE id = :id AND deleted = 0")
    suspend fun getById(id: Long): Checklist?

    @Query("SELECT * FROM checklists WHERE deleted = 0 AND (archived = 0 OR :includeArchived = 1) ORDER BY sortOrder ASC, name ASC")
    suspend fun getAll(includeArchived: Boolean): List<Checklist>

    /** Every non-deleted checklist regardless of [Checklist.archived] - the raw input to
     * `ChecklistController`'s "which checklists apply to a given day" read. **Trap 1's gate is
     * deliberately NOT expressed as SQL here.** [Checklist.createdAt] is a raw millisecond
     * timestamp; comparing it against a caller-supplied day boundary in SQL would mean baking a
     * timezone assumption into the query. The controller instead converts [Checklist.createdAt] to
     * a local [java.time.LocalDate] with `LocalDate.now()`'s own zone and compares DAYS, in Kotlin,
     * once - see `ChecklistController.checklistsForDay`'s own doc comment. */
    @Query("SELECT * FROM checklists WHERE deleted = 0")
    suspend fun getAllIncludingArchived(): List<Checklist>

    @Query("UPDATE checklists SET name = :name, updatedAt = :at WHERE id = :id")
    suspend fun rename(id: Long, name: String, at: Long)

    @Query("UPDATE checklists SET recursDaily = :recursDaily, updatedAt = :at WHERE id = :id")
    suspend fun setRecursDaily(id: Long, recursDaily: Boolean, at: Long)

    @Query("UPDATE checklists SET sortOrder = :sortOrder, updatedAt = :at WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int, at: Long)

    @Query("UPDATE checklists SET archived = 1, updatedAt = :at WHERE id = :id")
    suspend fun archive(id: Long, at: Long)

    @Query("UPDATE checklists SET archived = 0, updatedAt = :at WHERE id = :id")
    suspend fun unarchive(id: Long, at: Long)

    // Soft delete - mirrors ItemListDao.deleteById's own reasoning (a hard DELETE is invisible to
    // a future sync snapshot and would orphan every ChecklistItem/ChecklistTick row underneath it).
    @Query("UPDATE checklists SET deleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun deleteById(id: Long, at: Long)
}
