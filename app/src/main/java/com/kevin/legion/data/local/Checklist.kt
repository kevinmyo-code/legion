package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named, reusable checklist - "bio", "morning routine" - one row per list, its items in
 * [ChecklistItem]. `.scratch/one-today/issues/08-events-are-not-todos.md`'s follow-on (Kevin,
 * 2026-09-02): "bio, maintenance etc should all just be todos... i create the list, name it
 * something (bio) then under the list > 3 sets goblet squats etc etc. and i can make the list be
 * a daily reoccuring thing or not, then i tick off if i do it, end of day it records and resets."
 *
 * **There is no reset and no nightly job, deliberately.** "End of day it records and resets" is
 * the user-visible EFFECT, not the mechanism - every daily thing in this app is app-open-triggered
 * because there is no WorkManager dependency (see `sync/ScheduledBackup.kt`'s own doc comment for
 * that ruling), and a real midnight sweep would run twice, or skip three days, or fire at the wrong
 * hour after a timezone change. Instead a tick is a row keyed by `(item, day)` in [ChecklistTick] -
 * tomorrow queries tomorrow's [ChecklistTick.day] and finds nothing, which IS the reset, for free.
 *
 * [recursDaily] only changes how a day is READ, never what is stored: a recurring checklist is
 * queried for "today's" state on every day since [createdAt]; a non-recurring one is done the
 * moment ANY tick exists on ANY day (`ChecklistController.isNonRecurringDone`), and still records
 * WHEN each line was actually done, because it uses the exact same tick row shape (see
 * [ChecklistTick]'s own doc comment for why there is deliberately no second, one-shot storage
 * model). Toggling this later relabels how existing ticks are already being read; it does not
 * migrate or discard anything.
 *
 * **[createdAt] gates history, not just display.** A history read for a day before this checklist
 * existed must come back empty - never "every item, all unticked" - or deleting nothing and
 * creating "bio" today would make last month look like six weeks of missed workouts. Enforced in
 * `ChecklistController`, compared as a LOCAL DAY (`LocalDate`), never as a raw millisecond
 * comparison that a timezone change could shift across a day boundary.
 *
 * Follows [ItemList]'s own column conventions (name/sortOrder/archived/createdAt plus the four
 * sync columns) - the closest existing entity, and deliberately unrelated to it: [ItemList] is
 * FROZEN (see its own class doc) and this is a new table, not a repoint. **No sync code is wired
 * yet** - [updatedAt]/[syncId]/[serverId]/[deleted] are carried from day one, same "the columns
 * exist before the wiring" posture [ItemList]'s v9->v10 migration doc comment describes, so a
 * later sync ticket needs no second migration just to add the identity columns.
 */
@Entity(tableName = "checklists", indices = [Index(value = ["syncId"], unique = true)])
data class Checklist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** True: this checklist is read fresh every day (a "today" view). False: it is done once,
     * ever - the moment any tick exists on any of its items, on any day. See this class's own doc
     * comment for what flipping this later does and does not do.
     *
     * **DEPRECATED, one-today ticket 09's second build (2026-09-04).** [scheduleKind] now carries
     * this fact as the special case `scheduleKind = "DAILY", scheduleEvery = 1` - this column is
     * kept only because §5 forbids a destructive migration, and `MIGRATION_65_66` back-fills the
     * new columns from it for every existing row. `ChecklistController` reads only
     * [scheduleKind]/[scheduleEvery]/[scheduleDaysOfWeek] from now on; nothing writes this column
     * going forward. */
    val recursDaily: Boolean = true,
    /** Manual ordering in a future list-of-checklists screen. Not read by anything yet. */
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Hidden from the default list, kept whole - same posture as [ItemList.archived]. Distinct
     * from [deleted]: an archived checklist is still alive, just out of the way. */
    val archived: Boolean = false,
    // Last-modified epoch ms for cross-device sync last-write-wins, carried per this ticket's
    // brief even though nothing syncs yet - mirrors [ItemList.updatedAt]'s own reasoning.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    /** Not client-minted - null until a real sync round trip earns one (CLAUDE.md live-sync
     * ruling 5), same posture as [ItemList.serverId]. */
    val serverId: String? = null,
    // Soft-delete tombstone - a hard DELETE would cascade-orphan every ChecklistItem/ChecklistTick
    // row underneath it and would be invisible to a future sync snapshot, same reasoning
    // [ItemList.deleted]'s doc comment gives.
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** null: no schedule, this is a plain todo list that applies every day from [createdAt] until
     * archived. `"DAILY"`: applies every [scheduleEvery] days. `"WEEKLY"`: applies on the weekdays
     * in [scheduleDaysOfWeek], every [scheduleEvery] weeks. Stored as TEXT with no CHECK constraint,
     * same posture as [MeasureDirection]/[TickSource] - a third kind later needs no migration.
     * `ChecklistController.appliesOnDay` reuses [com.kevin.legion.notes.Recurrence] to decide
     * whether a given day matches, rather than a second hand-rolled recurrence engine. */
    val scheduleKind: String? = null,
    /** Every N days ([scheduleKind] `"DAILY"`) or weeks ([scheduleKind] `"WEEKLY"`). Null when
     * [scheduleKind] is null. */
    val scheduleEvery: Int? = null,
    /** `"WEEKLY"` only - comma-separated [java.time.DayOfWeek] names, the exact encoding
     * [ListItem.repeatDaysOfWeek] already uses ([com.kevin.legion.notes.formatWeekdays]/
     * [com.kevin.legion.notes.parseWeekdays]), reused rather than inventing a second one. Null for
     * every other [scheduleKind]. */
    val scheduleDaysOfWeek: String? = null,
)
