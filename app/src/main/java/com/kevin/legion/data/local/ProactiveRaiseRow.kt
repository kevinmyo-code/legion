package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One unprompted line the assistant actually raised - what fired, when, why, and whether it was
 * brushed off (ticket 02 call 3, `.scratch/proactive-mode/issues/02-trigger-engine.md`).
 *
 * **Why this table exists at all.** Before it, every raise hand-rolled its own "already said this"
 * state and **all of it was in memory**. `AriaForegroundService.kt` said so in its own words about
 * the odometer milestone: *"Process-life."* `recallChecked`, `overheatAnnounced`,
 * `lastMilestoneAnnounced`, `lastWeatherAlertAt` - all local fields, all lost the moment the process
 * died. The service is `START_STICKY` and ticket 07 established that Samsung's sleeping-apps layer
 * restarts things, so **"never nag twice" was not weakly enforced - it was impossible**, and would
 * have stayed impossible under any engine built on that state.
 *
 * One table is MEANT to back three separate promises, which is what justified the schema change.
 * **Two of the three are not wired yet (2026-08-21), and this comment previously implied all three
 * were live:** nothing calls [ProactiveRaiseDao.markDeclined], so no row is ever marked declined and
 * the suppression window cannot fire; and nothing calls [ProactiveRaiseDao.mostRecent], so there is
 * no tool for "why did you say that?" to read. **The daily cap is the only one actually running.**
 * Both gaps need a caller, not a schema change.
 *
 *  - **Never nag twice** - a declined raise suppresses its own [ruleId] for a window
 *    (ticket 08 call 3), silently, with the tone identical whenever it returns.
 *  - **The daily cap** - three spoken lines a day (ticket 05 call 3), counted off [spokenAt].
 *  - **"Why did you say that?"** - [reason] is stored at raise time, so the answer is a fact rather
 *    than the model reconstructing a plausible one (ticket 02 call 4).
 *
 * **Rows are written only when a raise actually got out**, never on a gated one. A gate that
 * silently refused is not something the assistant said, so it must not spend a slot or suppress a
 * later attempt.
 */
@Entity(tableName = "proactive_raises")
data class ProactiveRaiseRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * Which rule fired, e.g. `coolant_overheat`. **The suppression key** - a decline silences this
     * id, not the whole category, so brushing off a rest nudge never mutes an overheat warning.
     */
    val ruleId: String,
    /** [com.kevin.legion.service.ProactiveCategory.key]. */
    val category: String,
    /**
     * The falsifiable fact that fired the rule, in words - "coolant 118C, over the 110C threshold".
     * This is what "why did you say that?" reads back, and ticket 08 call 4 is explicit that it
     * **names the rule and the fact, never a justification**: a justification is unfalsifiable and a
     * fact is checkable, which is the same split the reconciliation gate makes (CLAUDE.md §4).
     */
    val reason: String,
    /** When it was raised. The daily cap counts these. */
    val spokenAt: Long,
    /** True once the reply is read as a brush-off. **Inferred, and imperfect** - a grunt may or may
     * not be a no (ticket 05 call 5). The cost of a wrong read is bounded on purpose: a false
     * decline loses one nudge, a missed decline means the rule returns on schedule. */
    val declined: Boolean = false,
    /**
     * How the line actually reached the user, so a later look at this table cannot mistake a
     * notification for something that was said aloud. One of [DELIVERY_SPOKEN] or
     * [DELIVERY_NOTIFIED] - never both, which is ticket 06 call 5's echo fix recorded as data.
     */
    val delivery: String = DELIVERY_SPOKEN,
) {
    companion object {
        const val DELIVERY_SPOKEN = "spoken"
        const val DELIVERY_NOTIFIED = "notified"
    }
}

@Dao
interface ProactiveRaiseDao {
    @Insert
    suspend fun insert(row: ProactiveRaiseRow): Long

    /** Spoken lines since [since]. **Only `spoken` counts against the daily cap** - the cap governs
     * speech, not existence (ticket 05 call 4), and a raise that fell back to a notification was
     * never spoken. */
    @Query(
        "SELECT COUNT(*) FROM proactive_raises WHERE spokenAt >= :since AND delivery = 'spoken' " +
            "AND category != :uncappedCategory"
    )
    suspend fun spokenCountSince(since: Long, uncappedCategory: String): Int

    /** The most recent raise of one rule, whatever its delivery - suppression follows the rule
     * having been surfaced at all, not merely having been spoken. */
    @Query("SELECT * FROM proactive_raises WHERE ruleId = :ruleId ORDER BY spokenAt DESC LIMIT 1")
    suspend fun lastForRule(ruleId: String): ProactiveRaiseRow?

    /** Marks the most recent raise of [ruleId] as brushed off. */
    @Query(
        "UPDATE proactive_raises SET declined = 1 WHERE id = " +
            "(SELECT id FROM proactive_raises WHERE ruleId = :ruleId ORDER BY spokenAt DESC LIMIT 1)"
    )
    suspend fun markDeclined(ruleId: String)

    /** Backs "why did you say that?" - the last thing raised, whatever it was. */
    @Query("SELECT * FROM proactive_raises ORDER BY spokenAt DESC LIMIT 1")
    suspend fun mostRecent(): ProactiveRaiseRow?
}
