package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a vehicle service record.
 */
@Entity(tableName = "service_records")
data class ServiceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String, // Vehicle.obdMac
    val serviceName: String,
    val mileage: Int,
    val date: Long, // Timestamp in milliseconds
    val cost: Double? = null, // dollars; null = no figure logged (feeds the build-sheet total)
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
