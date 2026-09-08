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

    /** Every label this table has ever seen, active or tombstoned - the existence check
     * [com.kevin.legion.engine.migration.EnginePlacesRetirementCopy] needs so a forgotten place
     * is never resurrected by copying its stale engine record back in. */
    @Query("SELECT label FROM places")
    suspend fun getAllLabels(): List<String>

    /**
     * Every ROW this table has ever seen, tombstones included - what
     * [com.kevin.legion.backend.PlacesSync] merges a server batch against.
     *
     * **[getAll] would be the wrong read there, and silently so.** It filters `deleted = 0`, so a
     * server tombstone arriving for a place already forgotten on this phone would find "no local
     * match" and be skipped - which is correct - but a server tombstone arriving for a row this
     * phone still holds ACTIVE is the case that matters, and any active-only read makes the two
     * indistinguishable the moment the local row is deleted first. Same "getAll(), not
     * getAllActive()" rule `EventsSync.pull` states in its own words, and the same reason
     * [com.kevin.legion.backend.BodyMerge.merge]'s `localRows` parameter is documented as
     * "INCLUDING already soft-deleted ones".
     *
     * A new `@Query`, not a schema change: no column added or dropped, so no migration and no
     * version bump follows it (CLAUDE.md section 5).
     */
    @Query("SELECT * FROM places")
    suspend fun getAllIncludingTombstones(): List<TaggedPlace>

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
