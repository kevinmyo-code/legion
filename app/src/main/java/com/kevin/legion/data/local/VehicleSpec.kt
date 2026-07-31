package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The "build details encyclopedia" for one car - its factory specs, keyed by the
 * same Vehicle.obdMac as everything else. Most fields are decoded from the VIN
 * via NHTSA vPIC ([com.kevin.legion.vehicle.VinDecoder]); the manual fields
 * ([paintColor]/[paintCode]/[buildNotes]) are driver-entered because vPIC can't
 * provide factory paint. One row per car (REPLACE on conflict); re-decoding
 * refreshes the decoded fields but preserves the manual ones.
 *
 * Recalls are NOT stored here - they're fetched live on request (NHTSA recalls
 * API) so they're never stale.
 */
@Entity(tableName = "vehicle_specs")
data class VehicleSpec(
    @PrimaryKey val vehicleId: String,   // Vehicle.obdMac
    val vin: String = "",
    // --- Powertrain (vPIC) ---
    val engineCylinders: Int? = null,
    val displacementL: Double? = null,
    val engineHp: Int? = null,
    val engineConfig: String = "",
    val fuelType: String = "",
    val transmissionStyle: String = "",
    val transmissionSpeeds: String = "",
    val driveType: String = "",
    // --- Identity / provenance (vPIC) ---
    val bodyClass: String = "",
    val doors: Int? = null,
    val series: String = "",
    val vehicleType: String = "",
    val manufacturer: String = "",
    val plantCity: String = "",
    val plantCountry: String = "",
    // --- Manual (driver-entered; VIN can't supply these) ---
    val paintColor: String = "",
    val paintCode: String = "",
    val buildNotes: String = "",
    // When the decoded fields were last refreshed (epoch ms; 0 = never).
    val decodedAt: Long = 0L,
    // Last-modified epoch ms for cross-device sync last-write-wins (S1). The Kotlin
    // default stamps new rows; an EDIT must re-stamp via copy(updatedAt =
    // System.currentTimeMillis()). DEFAULT '0' mirrors the migration.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
