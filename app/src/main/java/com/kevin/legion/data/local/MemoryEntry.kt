package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A long-term memory the driver explicitly asked Zero to remember
 * (trips, preferences, running jokes, etc.).
 */
@Entity(tableName = "memories")
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val timestamp: Long,
    // Portable cross-device identity for sync (S1): the local `id` autoincrements
    // per-device so it can't identify a row across the head unit and phone. A UUID
    // assigned at creation (SyncEngine backfills any blank legacy rows) is the union
    // key. Added v7->v8. The Kotlin default stamps a UUID on every new row; Room
    // uses the stored value when reading, and the SQL DEFAULT '' (mirroring the
    // migration) applies only to raw/migrated inserts.
    //
    // memory-supabase ticket (v60 -> v61, MIGRATION_60_61): this is ALSO the natural key
    // [com.kevin.legion.backend.MemoryBackend] upserts by server-side (`origin_guid`), exactly the
    // role [com.kevin.legion.data.local.BodyweightLog.guid] plays for body - reused rather than
    // adding a second, redundant identity column, since it already exists, is already unique per
    // row in practice, and is already minted client-side at construction time the same way `guid`
    // is. [MIGRATION_60_61] backfills any legacy blank value the same way [MIGRATION_59_60] does
    // for `guid`, then adds a unique index that was never there before.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    /** This row's server uuid once it has round-tripped a pull - null until then, same "never a
     * client-minted placeholder" posture [BodyweightLog.serverId]'s own doc comment states. */
    val serverId: String? = null,
    /** The last-write-wins clock [com.kevin.legion.backend.MemorySync.pull] compares against the
     * server's `updated_at` - stamped by [com.kevin.legion.backend.MemoryWriteThrough] at write
     * time, not derived from [timestamp] (a touch/recency refresh changes [timestamp] without that
     * necessarily being a sync-worthy edit... in practice every write bumps both together, but the
     * two are kept as separate columns rather than reusing [timestamp] for the sync clock, matching
     * [BodyweightLog.updatedAtMs] sitting beside `loggedAt` rather than replacing it). */
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0,
    /** Soft-delete tombstone flag - a row the driver rejected on the memory screen, or a tombstone
     * pulled from the server. Never physically removed by sync so a server tombstone can always
     * find its local match; [MemoryDao.deleteById] (the unconfigured-install / cancelled-pending-
     * create fast path) is the only hard delete. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
