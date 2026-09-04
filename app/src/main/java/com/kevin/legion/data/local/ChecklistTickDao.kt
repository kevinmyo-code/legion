package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for [ChecklistTick]. */
@Dao
interface ChecklistTickDao {
    /** IGNORE, not the default ABORT - double-ticking `(itemId, day)` must be a no-op, not a
     * constraint-violation crash. The unique index on `(itemId, day)` ([ChecklistTick]'s own class
     * doc) is what makes a same-day double-tick idempotent by construction rather than by an
     * existence check a second, concurrent caller could still race past. **This alone is NOT
     * enough for an untick-then-retick same day** - a soft-deleted row already occupies the unique
     * slot, so a plain re-`INSERT` is silently ignored and the tick never comes back. `retick`
     * below is `ChecklistController.tick`'s revival path for exactly that row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tick: ChecklistTick): Long

    @Query("SELECT * FROM checklist_ticks WHERE itemId = :itemId AND day = :day AND deleted = 0")
    suspend fun getForItemOnDay(itemId: Long, day: Int): ChecklistTick?

    /** By `(itemId, day)`, INCLUDING a soft-deleted row - `ChecklistController.tick`'s existence
     * check before deciding whether to [insert] a fresh row or [retick] an untombstoned one. */
    @Query("SELECT * FROM checklist_ticks WHERE itemId = :itemId AND day = :day")
    suspend fun getForItemOnDayIncludingDeleted(itemId: Long, day: Int): ChecklistTick?

    /** Revives a previously-untucked `(itemId, day)` row in place, with a fresh [ChecklistTick.tickedAt]
     * - re-ticking after an untick must reflect the NEW tap time, not the original one, exactly as
     * a brand-new row would via [insert]. Never called for a row that has no existing tombstone;
     * [ChecklistController.tick] chooses between this and [insert] based on
     * [getForItemOnDayIncludingDeleted]'s result. */
    @Query("UPDATE checklist_ticks SET deleted = 0, tickedAt = :tickedAt, updatedAt = :tickedAt WHERE itemId = :itemId AND day = :day")
    suspend fun retick(itemId: Long, day: Int, tickedAt: Long)

    /** Every tick for [itemId] regardless of [ChecklistTick.day] - `isNonRecurringDone`'s read:
     * a non-recurring checklist's item is "done" the moment ANY tick exists, on any day. */
    @Query("SELECT * FROM checklist_ticks WHERE itemId = :itemId AND deleted = 0")
    suspend fun allForItem(itemId: Long): List<ChecklistTick>

    /** Every tick across ALL of [itemIds] falling on [day] - one query per checklist-day read
     * rather than one query per item, for `ChecklistController.itemsWithTickState`. */
    @Query("SELECT * FROM checklist_ticks WHERE itemId IN (:itemIds) AND day = :day AND deleted = 0")
    suspend fun forItemsOnDay(itemIds: List<Long>, day: Int): List<ChecklistTick>

    /** Every tick across ALL of [itemIds] within a day range (inclusive both ends) - the history
     * read's one query, `ChecklistController.checklistHistory`. */
    @Query("SELECT * FROM checklist_ticks WHERE itemId IN (:itemIds) AND day BETWEEN :fromDay AND :toDay AND deleted = 0")
    suspend fun forItemsInRange(itemIds: List<Long>, fromDay: Int, toDay: Int): List<ChecklistTick>

    // Soft delete - untick. Matches this schema's tombstone posture everywhere else; a hard DELETE
    // would be invisible to a future sync snapshot the same way ItemListDao.deleteById's own doc
    // comment explains for item_lists.
    @Query("UPDATE checklist_ticks SET deleted = 1, updatedAt = :at WHERE itemId = :itemId AND day = :day")
    suspend fun untick(itemId: Long, day: Int, at: Long)
}
