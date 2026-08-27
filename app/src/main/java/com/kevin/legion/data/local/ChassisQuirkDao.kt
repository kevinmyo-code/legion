package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChassisQuirkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(quirks: List<ChassisQuirk>)

    /** All quirks whose chassis list contains the given chassis code. */
    @Query("SELECT * FROM chassis_quirks WHERE chassis LIKE '%' || :chassis || '%'")
    suspend fun getForChassis(chassis: String): List<ChassisQuirk>

    @Query("SELECT COUNT(*) FROM chassis_quirks")
    suspend fun count(): Int

    @Query("DELETE FROM chassis_quirks")
    suspend fun deleteAll()

    /** Every quirk on file - the upload source for [com.kevin.legion.backend.FleetReconcile]. Unlike
     * [CodeEventDao.getAllForUpload] and its siblings there is no vehicle to resolve here at all:
     * `chassis_quirks` is household-shared reference data, not a per-vehicle observation. */
    @Query("SELECT * FROM chassis_quirks")
    suspend fun getAll(): List<ChassisQuirk>
}
