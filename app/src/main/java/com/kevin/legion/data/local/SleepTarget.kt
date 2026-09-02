package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The driver's target minutes of sleep per night. Same copy-forward shape as [MealTarget]/
 * [BudgetTarget]/[WorkoutPlan] (ticket 05 D1/D2): a row is written only when the target CHANGES,
 * dated [effectiveFromDateEpoch], and [SleepTargetDao.currentTarget] reads the latest row on or
 * before the night in question - "copy forward" computed at read time, nothing ever deleted.
 *
 * No [com.kevin.legion.plan.TrustTier] column, matching [MealTarget]/[BudgetTarget]/
 * [WorkoutPlanItem]'s precedent: a target is an intention, not a claim about the world, so it sits
 * outside both trust tiers entirely (ticket 05 D3).
 *
 * **[guid]/[serverId]/[deleted] joined v59 -> v60 (body-supabase ticket), no separate
 * `updatedAtMs`** - see [MealTarget]'s own doc comment for why [updatedAt] doubles as the sync
 * clock here rather than a second column carrying the identical value.
 */
@Entity(
    tableName = "sleep_targets",
    indices = [
        Index(value = ["effectiveFromDateEpoch"], unique = true),
        Index(value = ["guid"], unique = true),
    ],
)
data class SleepTarget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetMinutes: Int,
    /** UTC day-start epoch millis - the first night (by [SleepLog.sleepDate]'s wake-date convention) this target applies to. */
    val effectiveFromDateEpoch: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val guid: String = "",
    val serverId: String? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
