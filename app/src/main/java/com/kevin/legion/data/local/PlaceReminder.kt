package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A reminder bound to a saved place (matches [TaggedPlace.label]), surfaced when
 * the driver arrives there - e.g. "remind me to grab my gym bag when I get to
 * the gym". Cleared (marked done) once acknowledged.
 */
@Entity(tableName = "place_reminders")
data class PlaceReminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeLabel: String,
    val text: String,
    val createdAt: Long,
    val done: Boolean = false,
    // Last-modified epoch ms for cross-device sync last-write-wins (S1). The Kotlin
    // default stamps new rows; an EDIT must re-stamp via copy(updatedAt =
    // System.currentTimeMillis()). DEFAULT '0' mirrors the migration.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
