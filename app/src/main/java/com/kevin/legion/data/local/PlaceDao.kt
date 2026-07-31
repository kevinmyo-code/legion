package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for [TaggedPlace].
 */
@Dao
interface PlaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: TaggedPlace)

    /** Active (not tombstoned) places. */
    @Query("SELECT * FROM places WHERE deleted = 0")
    suspend fun getAll(): List<TaggedPlace>

    // Soft delete (B19): a hard DELETE here is invisible to cross-device sync's
    // SELECT * snapshot, so the next pull re-inserts the row from a remote that
    // never saw it disappear. Flipping `deleted` + bumping `timestamp` (this
    // table's LWW clock column) makes the deletion a normal LWW fact that
    // propagates like any other edit.
    @Query("UPDATE places SET deleted = 1, timestamp = (strftime('%s','now') * 1000) WHERE label = :label")
    suspend fun delete(label: String)

    /** Tombstone GC (B19): hard-deletes rows tombstoned before [beforeMs] to cap storage growth. */
    @Query("DELETE FROM places WHERE deleted = 1 AND timestamp < :beforeMs")
    suspend fun purgeTombstones(beforeMs: Long)
}
