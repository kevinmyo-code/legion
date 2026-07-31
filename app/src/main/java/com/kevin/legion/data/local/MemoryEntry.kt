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
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
