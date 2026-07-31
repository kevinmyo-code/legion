package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One calendar day's driving rollup (E7) - the third, lightest tier below
 * monthly recaps and Yearly Wrapped. Deliberately has no cover art field:
 * unlike the monthly/yearly tiers, a daily log stays "single colored" (a
 * flat spine, no generated art, no image-generation cost) per Kevin's call
 * - a quick daily note, not an occasion the way pulling a monthly cassette
 * off the shelf is.
 */
@Entity(tableName = "daily_drive_logs")
data class DailyDriveLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val year: Int,
    val month: Int, // 1-12
    val day: Int, // 1-31
    val generatedAt: Long,
    val milesDriven: Double,
    val avgMpg: Double?,
    val driveCount: Int,
    val codeEventCount: Int,
    val narrative: String,
    val notable: Boolean = false,
    val notableReason: String? = null,
)
