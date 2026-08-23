package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [RecordType]. See [AspectDao]'s doc comment for why this stays thin
 * rather than routing through [com.kevin.legion.engine.RecordStore] - that door is for RECORDS. */
@Dao
interface RecordTypeDao {
    @Insert
    suspend fun insert(recordType: RecordType): Long

    @Update
    suspend fun update(recordType: RecordType)

    @Query("SELECT * FROM record_types WHERE id = :id")
    suspend fun getById(id: Long): RecordType?

    @Query("SELECT * FROM record_types WHERE aspectId = :aspectId ORDER BY id ASC")
    suspend fun listByAspect(aspectId: Long): List<RecordType>
}
