package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One proactive switch - the master, or one of the five categories (ticket 04 call 1,
 * `.scratch/proactive-mode/issues/04-categories-storage-and-surface.md`).
 *
 * **Room rather than SharedPreferences, deliberately.** Six booleans are trivially
 * SharedPreferences, and that was the alternative. Kevin's call: this is a setting about *him*, not
 * about a handset, and **two phones disagreeing about whether the assistant may speak is a real
 * failure** - one phone silent and one talking, with no way to tell which is right.
 *
 * **Stated honestly, because the ticket asked for it rather than an assertion: this does not make
 * the switches sync.** `sync/` has never actually executed. Putting them in Room makes them
 * *eligible* to sync and turns a guaranteed divergence into a bug that can be found. **Do not write
 * a comment anywhere claiming these sync until a real device pair has proved it.**
 *
 * Key/value rather than a six-column row on purpose: adding a category is then an enum constant and
 * a row, not a schema migration. The keys are [com.kevin.legion.service.ProactiveCategory.key] plus
 * [MASTER_KEY], and they are storage keys - **never rename one once a row exists under it.**
 */
@Entity(tableName = "proactive_settings")
data class ProactiveSetting(
    @PrimaryKey val key: String,
    val enabled: Boolean,
) {
    companion object {
        /** The master switch's row key. Not a category - it ANDs over all of them (settled
         * decision 2), so it deliberately does not live in `ProactiveCategory`. */
        const val MASTER_KEY = "master"
    }
}

@Dao
interface ProactiveSettingDao {
    @Query("SELECT * FROM proactive_settings")
    suspend fun all(): List<ProactiveSetting>

    @Query("SELECT enabled FROM proactive_settings WHERE key = :key LIMIT 1")
    suspend fun enabled(key: String): Boolean?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: ProactiveSetting)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(settings: List<ProactiveSetting>)
}
