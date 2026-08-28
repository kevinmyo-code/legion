package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [CarTask] (the voice-managed car to-do / wishlist). */
@Dao
interface CarTaskDao {
    @Insert
    suspend fun insert(task: CarTask): Long

    /** Open (not yet done, not tombstoned) items, oldest first. */
    @Query("SELECT * FROM car_tasks WHERE done = 0 AND deleted = 0 ORDER BY createdAt ASC")
    suspend fun getOpen(): List<CarTask>

    /** Every non-tombstoned row, done or not - [FleetReconcile][com.kevin.legion.backend.FleetReconcile]'s
     * upload source for the car_tasks fold into `events` (backend-erp ticket 10/06). Deliberately
     * NOT [getOpen]: that query also filters `done = 0`, which is correct for the phone's own todo
     * list UI but wrong for a cross-device upload - a task marked done on this phone must still
     * reach the server (as `structured_meta.done = true`) so the OTHER phone's fleet aspect sees it
     * as done rather than never seeing it disappear. */
    @Query("SELECT * FROM car_tasks WHERE deleted = 0")
    suspend fun getActiveForUpload(): List<CarTask>

    @Query("SELECT COUNT(*) FROM car_tasks WHERE done = 0 AND deleted = 0")
    suspend fun openCount(): Int

    // updatedAt is bumped in SQL (epoch ms) so cross-device sync LWW (S1) sees the
    // done-toggle as a fresh write; strftime('%s','now') is UTC seconds.
    @Query("UPDATE car_tasks SET done = 1, doneAt = :doneAt, updatedAt = (strftime('%s','now') * 1000) WHERE id = :id")
    suspend fun markDone(id: Long, doneAt: Long)

    // Soft delete (B19): a hard DELETE here is invisible to cross-device sync's
    // SELECT * snapshot, so the next pull re-inserts the row from a remote that
    // never saw it disappear. Flipping `deleted` + bumping `updatedAt` makes the
    // deletion a normal LWW fact that propagates like any other edit.
    @Query("UPDATE car_tasks SET deleted = 1, updatedAt = (strftime('%s','now') * 1000) WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Tombstone GC (B19): hard-deletes rows tombstoned before [beforeMs] to cap storage growth. */
    @Query("DELETE FROM car_tasks WHERE deleted = 1 AND updatedAt < :beforeMs")
    suspend fun purgeTombstones(beforeMs: Long)
}
