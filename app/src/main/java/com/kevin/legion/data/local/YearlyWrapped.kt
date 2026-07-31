package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One generated Yearly Wrapped (Sprint 6) - the richest of the three
 * Wrapped-family tiers (daily / monthly / yearly), aggregated from a year's
 * [MonthlyRecap] rows. Was originally an ephemeral, non-persisted data class
 * computed on demand - that meant a generated Wrapped never showed up on the
 * shelf, since the shelf only lists rows actually saved to the database.
 * Promoted to a real Room entity (2026-07-08) so it persists like the other
 * two tiers.
 */
@Entity(tableName = "yearly_wrapped")
data class YearlyWrapped(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: String,
    val year: Int,
    val generatedAt: Long,
    val milesDriven: Double,
    val driveCount: Int,
    val avgMpg: Double?,
    val longestDriveMiles: Double?,
    val notableMonths: Int,
    val codeEventCount: Int,
    val serviceCount: Int,
    val narrative: String,
    val coverImagePath: String?,
)
