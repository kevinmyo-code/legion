package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [ChecklistItem]. */
@Dao
interface ChecklistItemDao {
    @Insert
    suspend fun insert(item: ChecklistItem): Long

    @Query("SELECT * FROM checklist_items WHERE id = :id AND deleted = 0")
    suspend fun getById(id: Long): ChecklistItem?

    /** By-id, INCLUDING soft-deleted - trap 2's read: a history line still needs to resolve a
     * dropped item's [ChecklistItem.text] even after it is soft-deleted, so this is the lookup
     * `ChecklistController.checklistHistory` uses, never [getById]. */
    @Query("SELECT * FROM checklist_items WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: Long): ChecklistItem?

    @Query("SELECT * FROM checklist_items WHERE checklistId = :checklistId AND deleted = 0 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun forChecklist(checklistId: Long): List<ChecklistItem>

    /** Every item ever belonging to [checklistId], INCLUDING soft-deleted ones - the history
     * read's own source list, so a dropped item's past ticks still have a row to resolve text
     * against without a second per-tick lookup. */
    @Query("SELECT * FROM checklist_items WHERE checklistId = :checklistId ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun forChecklistIncludingDeleted(checklistId: Long): List<ChecklistItem>

    @Query("UPDATE checklist_items SET text = :text, updatedAt = :at WHERE id = :id")
    suspend fun updateText(id: Long, text: String, at: Long)

    @Query("UPDATE checklist_items SET sortOrder = :sortOrder, updatedAt = :at WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int, at: Long)

    // Soft delete only - trap 2 ("editing or deleting an item must not rewrite the past"). Ticks
    // in checklist_ticks are never touched by this call; see ChecklistTick's own class doc.
    @Query("UPDATE checklist_items SET deleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun deleteById(id: Long, at: Long)
}
