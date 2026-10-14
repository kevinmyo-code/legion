package com.kevin.midnightai.data.local

import androidx.room.Dao
import androidx.room.Query

/**
 * Data Access Object for [CarTask]. The car to-do feature (voice tools, Logbook
 * TO-DO tab, prompt awareness, sync) was RETIRED 2026-10-13 (Kevin): to-dos live
 * in the LEGION engine now, reached via ask_engine. The `car_tasks` table stays
 * in the schema because Room migrations are additive-only (CLAUDE.md section 9);
 * the only thing left to do with it is empty it.
 */
@Dao
interface CarTaskDao {
    /** Drops every saved car to-do. Idempotent; run off-main at service start. */
    @Query("DELETE FROM car_tasks")
    suspend fun deleteAll()
}
