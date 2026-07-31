package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single line in the car's build sheet / spend ledger - the unified record
 * behind the logbook. Covers mods, parts, repairs, consumables, and general
 * spend (routine maintenance with a schedule still lives in [ServiceRecord] /
 * [MaintenanceItem]; the logbook view merges both into one timeline).
 *
 * [cost] is nullable on purpose: the driver can log *what* was done without a
 * dollar figure, and the money layer is hidden/gated separately. Cost is in
 * dollars (this is a personal spend log, not accounting).
 */
@Entity(tableName = "build_entries")
data class BuildEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,          // Vehicle.obdMac
    val type: String,               // mod | part | repair | consumable | other
    val title: String,
    val vendor: String = "",        // where bought / shop
    val partNumber: String = "",
    val cost: Double? = null,       // dollars; null = no figure logged
    val date: Long,                 // when (epoch ms)
    val mileage: Int? = null,
    val notes: String = "",
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
