package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [WidgetInstance]. Plain CRUD - no reference/computed concerns, so this
 * does not route through [com.kevin.legion.engine.RecordStore] (same reasoning as [AspectDao]). */
@Dao
interface WidgetInstanceDao {
    @Insert
    suspend fun insert(widget: WidgetInstance): Long

    @Update
    suspend fun update(widget: WidgetInstance)

    @Delete
    suspend fun delete(widget: WidgetInstance)

    @Query("SELECT * FROM widget_instances WHERE id = :id")
    suspend fun getById(id: Long): WidgetInstance?

    @Query("SELECT * FROM widget_instances WHERE deviceId = :deviceId ORDER BY position ASC")
    suspend fun forDevice(deviceId: String): List<WidgetInstance>

    /** One page's widgets - `aspectId IS NULL` for the home page, a specific aspect id for that
     * aspect's own page (ticket 18's "home page one, one page per aspect", [WidgetInstance]'s own
     * "a page IS an aspect" doc). Room's `:aspectId` binds a Kotlin `null` to a real SQL `NULL`, so
     * `aspectId IS :aspectId` reads correctly for both the home case and every aspect page with one
     * query rather than two near-duplicate ones. */
    @Query("SELECT * FROM widget_instances WHERE deviceId = :deviceId AND aspectId IS :aspectId ORDER BY position ASC")
    suspend fun forDevicePage(deviceId: String, aspectId: Long?): List<WidgetInstance>

    /** Whether [com.kevin.legion.engine.DefaultArrangementSeeder] has anything to do at all for
     * [deviceId] - an empty table (fresh install, or a device that has never opened the pager) is
     * the ONE condition that triggers seeding; any non-zero count means a layout already exists,
     * hand-arranged or previously seeded, and must never be overwritten. */
    @Query("SELECT COUNT(*) FROM widget_instances WHERE deviceId = :deviceId")
    suspend fun countForDevice(deviceId: String): Int
}
