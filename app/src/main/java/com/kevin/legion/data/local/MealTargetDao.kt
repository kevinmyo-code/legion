package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [MealTarget]. */
@Dao
interface MealTargetDao {
    /** REPLACE on the unique `effectiveFromDateEpoch` index, same shape as [BudgetTargetDao.upsert]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: MealTarget)

    /** Whole-row update - [com.kevin.legion.backend.BodySync.pull]'s merge write. Body-supabase
     * ticket; never called by [com.kevin.legion.meals.MealController], which always writes a NEW
     * effective-dated row via [upsert] rather than editing one in place. */
    @Update
    suspend fun update(target: MealTarget)

    /** Every row, active AND soft-deleted (body-supabase ticket) - see
     * [BodyweightLogDao.getAll]'s own doc comment for why. */
    @Query("SELECT * FROM meal_targets")
    suspend fun getAll(): List<MealTarget>

    /** The EXACT row already effective from [dateStartMs], if one exists - body-supabase ticket.
     * [com.kevin.legion.backend.BodyWriteThrough.setMealTarget]'s one caller
     * ([com.kevin.legion.meals.MealController.setTarget]) reads this FIRST and reuses that row's
     * own [MealTarget.guid] on [upsert]'s `REPLACE` rather than minting a fresh one: `REPLACE`
     * deletes and reinserts on the unique `effectiveFromDateEpoch` conflict, so setting the SAME
     * day's target twice with two different guids would upsert two DIFFERENT server rows
     * (`origin_guid` is the server's own conflict key) and orphan the first one forever, never
     * softDeleted, never converging with the local REPLACE that already discarded it. */
    @Query("SELECT * FROM meal_targets WHERE effectiveFromDateEpoch = :dateStartMs LIMIT 1")
    suspend fun getByEffectiveDate(dateStartMs: Long): MealTarget?

    /** The target effective as of [dateStartMs] - the latest row on or before it. `deleted = 0`
     * added body-supabase ticket: a target tombstoned by a remote delete must not still read as
     * the current one. */
    @Query(
        "SELECT * FROM meal_targets WHERE deleted = 0 AND effectiveFromDateEpoch = (" +
            "SELECT MAX(effectiveFromDateEpoch) FROM meal_targets WHERE deleted = 0 AND effectiveFromDateEpoch <= :dateStartMs" +
            ")"
    )
    suspend fun currentTarget(dateStartMs: Long): MealTarget?
}
