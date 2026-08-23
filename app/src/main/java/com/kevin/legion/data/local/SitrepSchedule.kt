package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The sitrep's newsletter sender list - a SIBLING table to [SitrepModuleSetting] rather than
 * columns bolted onto it (ticket 22's own call: "your call, document it"). Reasoning:
 * [SitrepModuleSetting] is a key/value ROW PER MODULE, so a sender list has nowhere honest to live
 * on that shape without either duplicating it on every module row or picking one arbitrary
 * module's row to smuggle it onto. A second table with exactly one row (see [ID]) says plainly
 * "this is the one sender list, not a per-module setting."
 *
 * **Newsletter senders are one comma-separated [String]** ([senders]), not a child table -
 * ticket 08's resolution §6 is explicit that "the newsletter sender list is curated by Kevin, by
 * hand," so this is a short, rarely-edited list a human types once on a settings screen, not data
 * with its own lifecycle worth a table and a DAO of its own. [com.kevin.legion.sitrep.SitrepBuilder]
 * is the one place that parses it back into individual addresses for the Gmail query.
 *
 * **`hour`/`minute` are VESTIGIAL, not live** (ticket 32, Kevin: "sitreps stay tap only or via
 * voice activation only"). They used to be the scheduled sitrep's fire time, plain ints in
 * device-local time, re-armed daily by the alarm ticket 32 retired
 * ([com.kevin.legion.sitrep.SitrepScheduler] and `SitrepAlarmReceiver` are both gone). Kept
 * rather than dropped because they are non-null columns on a table this file's own `senders`
 * column still needs - CLAUDE.md §5's "an unused table is cheaper than a destructive migration"
 * applies the same way to unused columns on a table still in use. Every write goes through
 * [com.kevin.legion.sitrep.SitrepSettings.setNewsletterSenders], which always persists `0`/`0`;
 * nothing reads them back.
 */
@Entity(tableName = "sitrep_schedule")
data class SitrepSchedule(
    @PrimaryKey val id: Int = ID,
    val hour: Int,
    val minute: Int,
    /** Comma-separated newsletter sender addresses/domains, e.g.
     * "newsletter@stratechery.com,digest@therundown.ai". Blank means the NEWS module has nothing
     * to fetch, and [com.kevin.legion.sitrep.SitrepBuilder] says so in words rather than silently
     * fetching nothing. */
    val senders: String,
) {
    companion object {
        /** The one and only row this table ever holds - a schedule is a singleton, not a list. */
        const val ID = 0
    }
}

@Dao
interface SitrepScheduleDao {
    @Query("SELECT * FROM sitrep_schedule WHERE id = ${SitrepSchedule.ID} LIMIT 1")
    suspend fun get(): SitrepSchedule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(schedule: SitrepSchedule)
}
