package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One sitrep module switch (ticket 22, `.scratch/hands-and-senses/issues/22-build-the-sitrep.md`)
 * - CALENDAR/WEATHER/FLEET/NEWS on or off. **Copied shape-for-shape from
 * [ProactiveSetting]**, per that ticket's own instruction ("Follow `data/local/ProactiveSetting.kt`
 * exactly as the pattern - it is the same shape and was written yesterday"): key/value Room rather
 * than SharedPreferences, for the identical reason - "this is a setting about Kevin, not about a
 * handset" (ticket 08's resolution §6). Same honest caveat too: this makes the switches ELIGIBLE
 * to sync, not synced - `sync/` has never actually executed, and nothing here should claim
 * otherwise until a real device pair proves it.
 *
 * The keys are [com.kevin.legion.sitrep.SitrepModule.key] - storage keys, never renamed once a row
 * exists under one.
 */
@Entity(tableName = "sitrep_modules")
data class SitrepModuleSetting(
    @PrimaryKey val key: String,
    val enabled: Boolean,
)

@Dao
interface SitrepModuleSettingDao {
    @Query("SELECT * FROM sitrep_modules")
    suspend fun all(): List<SitrepModuleSetting>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SitrepModuleSetting)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(settings: List<SitrepModuleSetting>)
}
