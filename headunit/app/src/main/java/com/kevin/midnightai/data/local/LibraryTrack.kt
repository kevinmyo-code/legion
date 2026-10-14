package com.kevin.midnightai.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One imported audio file in the general library (the "drawer"). Files are
 * copied into filesDir/library/<uuid>.<ext> on import (see MixtapeLibrary) so
 * playback never depends on a USB stick staying plugged in or a SAF grant
 * surviving reboot. A track can belong to zero or many [Mixtape]s via
 * [MixtapeTrack] - removing it from a tape does not delete this row or file.
 */
@Entity(tableName = "library_track")
data class LibraryTrack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,       // absolute path under filesDir/library/
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sourceName: String,     // original filename, for display when tags are blank
    val addedAt: Long,
    // Last-modified epoch ms for cross-device sync last-write-wins (S1). Added
    // v6->v7. 0 = unstamped; SyncEngine wires write-stamping. DEFAULT '0' mirrors
    // the migration so a migrated row validates identically to a fresh one.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)
