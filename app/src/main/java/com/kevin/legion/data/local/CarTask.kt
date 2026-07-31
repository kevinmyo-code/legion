package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A freeform, voice-managed car to-do / wishlist item the driver mentioned:
 * maintenance they want to get to ("change the oil soon", "replace the bushings"),
 * a future project or build ("LS swap", "new coilovers"), or an accessory to buy
 * ("new wheels", "a light bar").
 *
 * This is the open-ended companion to [MaintenanceItem], which is the *scheduled*
 * maintenance with mileage/time intervals. Kept global (not keyed to a vehicle)
 * so an item added while the OBD adapter isn't connected can't silently vanish
 * when a different profile becomes active.
 */
@Entity(tableName = "car_tasks")
data class CarTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** Rough bucket Zero assigns: "maintenance", "project", or "wishlist" (free string). */
    val category: String,
    val done: Boolean = false,
    val createdAt: Long,
    val doneAt: Long? = null,
    // Last-modified epoch ms for cross-device sync last-write-wins (S1). The Kotlin
    // default stamps new rows at construction; an EDIT must re-stamp via
    // copy(updatedAt = System.currentTimeMillis()) or LWW can't tell which side is
    // newer. DEFAULT '0' mirrors the migration for raw/migrated rows.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    // Soft-delete tombstone (B19): remove_car_task flips this instead of a hard
    // DELETE, so the deletion is a syncable LWW fact (via `updatedAt`) rather than
    // a local-only mutation the next sync would silently resurrect from a remote
    // snapshot that never saw it disappear. All active-row reads must filter
    // `deleted = 0`; only the sync SELECT and tombstone GC see deleted rows.
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
