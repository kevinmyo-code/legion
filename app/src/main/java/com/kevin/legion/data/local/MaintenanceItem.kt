package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A recurring maintenance task for a vehicle (e.g. "Oil Change" every 5,000
 * miles / 6 months). Intervals are seeded from an online lookup based on the
 * vehicle's make/model/year, and [lastDoneMileage]/[lastDoneDate] update as
 * the driver logs completed work.
 */
@Entity(tableName = "maintenance_items", primaryKeys = ["vehicleId", "serviceName"])
data class MaintenanceItem(
    val vehicleId: String,
    val serviceName: String,
    val intervalMiles: Int? = null,
    val intervalMonths: Int? = null,
    val lastDoneMileage: Int? = null,
    val lastDoneDate: Long? = null,
    // Last-modified epoch ms for cross-device sync last-write-wins (S1). The Kotlin
    // default stamps new rows; an EDIT must re-stamp via copy(updatedAt =
    // System.currentTimeMillis()). DEFAULT '0' mirrors the migration.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // "Never been done" is a KNOWN, actionable fact ("I've never rotated the tires
    // on this car") and is deliberately distinct from an UNKNOWN anchor (both
    // lastDone* fields null because the driver never said anything). neverDone
    // items are always overdue; unknown items are never treated as due at all -
    // conflating the two used to make a high-mileage car's entire schedule read
    // as due the moment it was seeded, because every freshly-looked-up interval
    // starts with null anchors. See VehicleController.dueItems/unknownItems.
    @ColumnInfo(defaultValue = "0") val neverDone: Boolean = false,
)
