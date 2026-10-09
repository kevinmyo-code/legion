package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A place the driver tagged by voice ("this is my work"), stored so Aria can
 * recognise when they're there and reference it naturally. The label is the
 * primary key, so re-tagging an existing label (e.g. moving jobs) overwrites
 * the old coordinates.
 */
@Entity(tableName = "places")
data class TaggedPlace(
    @PrimaryKey val label: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    // Soft-delete tombstone (B19): forget_place flips this instead of a hard
    // DELETE, so the deletion is a syncable LWW fact (via `timestamp`, this
    // table's clock column) rather than a local-only mutation the next sync
    // would silently resurrect from a remote snapshot that never saw it
    // disappear. All active-row reads must filter `deleted = 0`; only the sync
    // SELECT and tombstone GC see deleted rows.
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    // The human address (2026-10-09, `MIGRATION_73_74`): shown and spoken wherever the place is.
    // The coordinates above stay the place - geofences, navigation and distance need them - and
    // this names them. Null on every place saved before it, and on one saved where the user
    // stood when the address lookup failed (said so in words, never guessed).
    val address: String? = null,
)
