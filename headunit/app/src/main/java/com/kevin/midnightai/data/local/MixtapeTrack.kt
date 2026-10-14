package com.kevin.midnightai.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Ordered membership join: which [LibraryTrack]s are on which [Mixtape], and in what order. */
@Entity(tableName = "mixtape_track")
data class MixtapeTrack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mixtapeId: Long,
    val libraryTrackId: Long,
    val sortOrder: Int,
    val addedAt: Long,
    // Last-modified epoch ms for cross-device sync last-write-wins (S1). Added
    // v6->v7. 0 = unstamped; SyncEngine wires write-stamping. DEFAULT '0' mirrors
    // the migration so a migrated row validates identically to a fresh one.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)
