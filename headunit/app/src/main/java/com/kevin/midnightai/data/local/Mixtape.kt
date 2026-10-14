package com.kevin.midnightai.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** A driver-assembled mixtape: a named, ordered playlist with its own generated cover. */
@Entity(tableName = "mixtape")
data class Mixtape(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverImagePath: String? = null,  // filesDir/mixtapes/<id>.png once generated
    val coverPrompt: String? = null,     // last preset/freeform prompt used, for regen
    val createdAt: Long,
    val sortOrder: Int = 0,
    // Last-modified epoch ms for cross-device sync last-write-wins (S1). Added
    // v6->v7. 0 = unstamped; SyncEngine wires write-stamping. DEFAULT '0' mirrors
    // the migration so a migrated row validates identically to a fresh one.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)
