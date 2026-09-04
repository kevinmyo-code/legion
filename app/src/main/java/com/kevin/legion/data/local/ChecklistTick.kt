package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One completion of a [ChecklistItem] on one day - the entire "record and reset" mechanism from
 * this ticket's brief, and it is a mechanism with no clock in it: tomorrow queries tomorrow's
 * [day] and finds no row, which reads as "not done yet" for free, with no midnight job to run it
 * (see [Checklist]'s own class doc for why a real nightly sweep was rejected). A missed day reads
 * as missed because no row was ever written for it, not because a job decided so after the fact.
 *
 * **[day] and [tickedAt] are deliberately different facts, and both are stored.** [day] is the day
 * the tick COUNTS FOR; [tickedAt] is the real-world instant the user actually tapped. They differ
 * whenever someone ticks yesterday's row this morning - collapsing them into one column would make
 * that retroactive tick indistinguishable from a same-day one, which is exactly the kind of
 * silently-wrong history this ticket's brief calls out by name. [day] is a local epoch day
 * (`java.time.LocalDate.toEpochDay()`), not a millisecond timestamp - a `LocalDate` has no
 * timezone in it, so "which day this counts for" cannot shift under a timezone change the way a
 * millisecond-range comparison could.
 *
 * **One tick model, not two** (this ticket's brief, verbatim: "do not add a `done` boolean to
 * `checklist_items`"). A NON-recurring checklist's "done" state is derived by
 * `ChecklistController.isNonRecurringDone` as "a tick exists on ANY day for this item", read
 * against these same rows - so a one-shot checklist still records WHEN each line was actually
 * done, for free, rather than needing a second, dateless completion table alongside this one.
 *
 * The unique index on `(itemId, day)` is what makes ticking twice one tick, not two -
 * `ChecklistDao.tick`'s `INSERT OR IGNORE` (never a plain `INSERT`) relies on this constraint to
 * make a double-tick idempotent by construction rather than by an application-level existence
 * check that a second, concurrent caller could still race past.
 */
@Entity(
    tableName = "checklist_ticks",
    indices = [Index(value = ["itemId", "day"], unique = true), Index("day")],
)
data class ChecklistTick(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    /** Local epoch day (`LocalDate.toEpochDay()`) this tick COUNTS FOR - see this class's own doc
     * comment for why this is not the same fact as [tickedAt]. */
    val day: Int,
    /** Wall-clock epoch ms of the actual tap - may fall on a different calendar day than [day]
     * when the tick is retroactive (catching up yesterday's row this morning). */
    val tickedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    val serverId: String? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
