package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One known-issue entry from the bundled Chassis Quirk Index (assets/quirks.json).
 * Parsed to Room on first launch and re-synced on APK updates. The content is
 * curated by the community (GitHub PRs for v2+) so each platform added is also
 * a marketing moment.
 *
 * chassis: a comma-delimited list of chassis codes this quirk applies to, e.g.
 *   "E46,E46M3" or "XJ,XJ_4.0". Allows one quirk entry to cover a platform family.
 * engine: optional engine-code filter (blank = applies to all engines on that chassis).
 * mileageLow/High: typical onset mileage window; -1 = no bound.
 * severity: "MONITOR", "SERVICE_SOON", "CRITICAL"
 * costLow/High: typical repair cost range in USD; -1 = unknown/DIY-variable.
 */
@Entity(tableName = "chassis_quirks")
data class ChassisQuirk(
    @PrimaryKey val quirkId: String,    // stable slug, e.g. "e46_subframe_crack"
    val chassis: String,
    val engine: String = "",
    val title: String,
    val symptom: String,
    val verificationSteps: String,
    val mileageLow: Int = -1,
    val mileageHigh: Int = -1,
    val severity: String,               // "MONITOR" | "SERVICE_SOON" | "CRITICAL"
    val costLow: Int = -1,
    val costHigh: Int = -1,
    val fixNotes: String = "",
    val sourceUrl: String = "",
    // Last-modified epoch ms for cross-device sync last-write-wins (S1). The Kotlin
    // default stamps new rows; an EDIT must re-stamp via copy(updatedAt =
    // System.currentTimeMillis()). DEFAULT '0' mirrors the migration.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
