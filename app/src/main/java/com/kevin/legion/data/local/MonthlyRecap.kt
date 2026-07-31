package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One generated monthly recap cassette (E5) - a Side A cover image + Side B
 * narrative summarizing a calendar month's driving. One row per vehicle per
 * month; [MonthlyRecapController] generates these automatically shortly after
 * a month rolls over.
 *
 * [notable]/[notableReason] flag an unusual month (e.g. an especially long
 * drive) - feeds the future shelf/archive UI's special spine coloring
 * (Kevin's design vision), not used for anything yet in this first pass.
 */
@Entity(tableName = "monthly_recaps")
data class MonthlyRecap(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val year: Int,
    val month: Int, // 1-12
    val generatedAt: Long,
    val milesDriven: Double,
    val avgMpg: Double?,
    val driveCount: Int,
    val longestDriveMiles: Double?,
    val codeEventCount: Int,
    val serviceCount: Int,
    val narrative: String,
    val coverImagePath: String?,
    val notable: Boolean = false,
    val notableReason: String? = null,
)
