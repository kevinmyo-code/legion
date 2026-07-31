package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One OBD-II telemetry sample, captured every 30 s while driving.
 * The raw PID string (e.g. "0104" = engine load) is stored alongside
 * its decoded value so the schema stays generic across all PIDs.
 * GPS fields are nullable — they fill in only when location is live.
 *
 * Estimated storage: 18 MB/year at 30-second intervals across a normal
 * driving routine. This is the moat table: a compounding time series that
 * no competitor has because no competitor is embedded in the car.
 */
@Entity(tableName = "obd_samples")
data class OdbSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,      // Vehicle.obdMac
    val pid: String,            // raw PID code, e.g. "0104"
    val value: Double,          // decoded numeric value in SI/imperial unit
    val unit: String,           // human label, e.g. "rpm", "°F", "V"
    val timestamp: Long,        // System.currentTimeMillis()
    val lat: Double? = null,
    val lng: Double? = null,
)
