package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The sitrep's schedule and its newsletter sender list - a SIBLING table to
 * [SitrepModuleSetting] rather than columns bolted onto it (ticket 22's own call: "your call,
 * document it"). Reasoning: [SitrepModuleSetting] is a key/value ROW PER MODULE, so a schedule
 * time or a sender list has nowhere honest to live on that shape without either duplicating it on
 * every module row or picking one arbitrary module's row to smuggle it onto. A second table with
 * exactly one row (see [ID]) says plainly "this is the one schedule, not a per-module setting."
 *
 * **Newsletter senders are one comma-separated [String]** ([senders]), not a child table -
 * ticket 08's resolution §6 is explicit that "the newsletter sender list is curated by Kevin, by
 * hand," so this is a short, rarely-edited list a human types once on a settings screen, not data
 * with its own lifecycle worth a table and a DAO of its own. [com.kevin.legion.sitrep.SitrepBuilder]
 * is the one place that parses it back into individual addresses for the Gmail query.
 *
 * **`hour`/`minute` are plain ints in device-local time**, matching [ListItem]'s own local-time
 * convention for a reminder rather than an epoch millis "next fire" (an epoch value would go stale
 * the moment [com.kevin.legion.sitrep.SitrepScheduler] re-arms it for the next day - the intent is
 * "8:30 every morning," not "8:30 on 2026-08-22 specifically").
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
