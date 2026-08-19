package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kevin.legion.plan.TrustTier

/**
 * One logged night's sleep (Kevin, 2026-08-07: "i want to be able to log sleep too"). Modelled
 * directly on [MealLog]/[WorkoutSetLog] - a new body domain, same REPORTED-tier shape, no
 * reconciliation gate (CLAUDE.md §4's gate only applies where an external document states its own
 * anchor to check extraction against; nothing external ever verifies how long a person slept).
 *
 * [sleepDate] is the LOCAL midnight of the night's WAKE date, not the night it started - sleeping
 * Monday night into Tuesday morning is logged under Tuesday, matching
 * [com.kevin.legion.meals.dayStartEpoch]'s "today" convention everywhere else in the app
 * ("today's gap" always means the day the driver is currently living, and a night's sleep is
 * naturally reported after waking up, i.e. on the day it ends). This is a documented CHOICE, not
 * an accident: the alternative (keying by the night it STARTED) would make "how did I sleep last
 * night" and "how did I sleep today" disagree about which row answers the question.
 *
 * [durationMinutes] is `Int`, not `Double` hours - CLAUDE.md's "money is `Long` cents, never
 * `Double`" exactness discipline applied to the one other unit in this app a spoken decimal
 * ("7.5 hours") could silently round wrong; [com.kevin.legion.sleep.parseSleepDurationMinutes]
 * is where that rounding happens exactly once, at the write boundary, never re-derived downstream.
 *
 * [quality] is an optional 1-5 self-rating, nullable because the driver may simply not say one.
 * [syncId] follows [LedgerTransaction.syncId]'s convention (a random UUID stamped at write time),
 * ahead of this row ever needing Drive sync - see that field's own doc comment.
 */
@Entity(tableName = "sleep_logs")
data class SleepLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sleepDate: Long,
    val durationMinutes: Int,
    val quality: Int? = null,
    val notes: String? = null,
    val loggedAt: Long,
    val trustTier: TrustTier,
    val syncId: String = java.util.UUID.randomUUID().toString(),
)
