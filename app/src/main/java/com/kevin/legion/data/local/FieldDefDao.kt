package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [FieldDef]. See [AspectDao]'s doc comment for why this stays thin. */
@Dao
interface FieldDefDao {
    @Insert
    suspend fun insert(fieldDef: FieldDef): Long

    @Update
    suspend fun update(fieldDef: FieldDef)

    @Query("SELECT * FROM field_defs WHERE id = :id")
    suspend fun getById(id: Long): FieldDef?

    @Query("SELECT * FROM field_defs WHERE recordTypeId = :recordTypeId ORDER BY position ASC, id ASC")
    suspend fun forRecordType(recordTypeId: Long): List<FieldDef>

    /** Every [FieldType.REFERENCE] field across every record type - [com.kevin.legion.engine.RecordStore]
     * scans this to find which OTHER record types point at the one being deleted. */
    @Query("SELECT * FROM field_defs WHERE type = 'REFERENCE'")
    suspend fun allReferenceFields(): List<FieldDef>

    /** Every [FieldType.COMPUTED] field across every record type - scanned to find which aggregate
     * expressions are watching a record type that just changed. */
    @Query("SELECT * FROM field_defs WHERE type = 'COMPUTED'")
    suspend fun allComputedFields(): List<FieldDef>

    @Query("DELETE FROM field_defs WHERE id = :id AND locked = 0")
    suspend fun deleteIfUnlocked(id: Long): Int
}
