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

    @Query("SELECT * FROM widget_instances WHERE deviceId = :deviceId ORDER BY position ASC")
    suspend fun forDevice(deviceId: String): List<WidgetInstance>
}
