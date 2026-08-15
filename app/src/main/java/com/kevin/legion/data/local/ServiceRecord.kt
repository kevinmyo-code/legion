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
    // Cents, never dollars - CLAUDE.md §4 rule 3. Migrated from `cost: Double?` at
    // v19->v20 (ticket 11, `.scratch/fleet-maintenance/issues/11-*`): the column had
    // NO writer anywhere in the app and was null on both of Kevin's real records, so
    // the migration is a straight rename-and-retype with nothing to convert - see
    // MIGRATION_19_20's doc comment for the create/copy/drop/rename mechanics this
    // needed (SQLite cannot ALTER a column's type in place) and CLAUDE.md §5 for why
    // this is the one non-additive exception on this map. null = no figure logged
    // (feeds the build-sheet total and fleet spend, which must say how many records
    // they cover rather than silently treating a cost-less row as $0).
    val costCents: Long? = null,
    // Portable cross-device identity for sync (S1) - see MemoryEntry.syncId.
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
)
