package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of the legacy `list_items` table, and nothing else.
 *
 * **Split out of [ListItem] by the lists redesign slice 1a (2026-11-24), because one name was
 * carrying two unrelated stores.** `ListItem` was simultaneously a Room `@Entity` for this table
 * AND the in-memory shape `notes/NotesController.kt` returns while reading `events` (its
 * `Event.toListItem` mapper). A reader could not tell from a signature which store a `ListItem`
 * had come from, and the two had already diverged in meaning: `syncId` is a server uuid on one
 * path and a client-minted placeholder on the other, and `listId` is a real foreign key here and
 * a display convenience there. [ListItem] is now a plain data class with the single in-memory
 * meaning; this entity is the table.
 *
 * **FROZEN, and read by exactly one caller.** `notes/NotesController.kt` repointed every live read
 * and write onto `events` in backend-erp ticket 15 step 4, and the legacy Supabase sync of this
 * table retired in the same slice that split this class out. What is left is
 * [com.kevin.legion.engine.migration.EngineDataMigrationWave1]'s one-time, flag-guarded notes copy
 * and its `ListItemSkip` rekey, both wired at app start; see that object's own class doc. Nothing
 * writes this table at all. It stays a registered entity until that migrator retires with the
 * aspect engine, at which point the entity, [LegacyListItemDao] and the table's registration go
 * together (Room is additive only, CLAUDE.md section 5, so the table itself stays either way).
 *
 * The column set is verbatim what the table has carried since `MIGRATION_62_63`; see [ListItem]'s
 * own doc comment for what each one meant when this table was live, and
 * `docs/architecture/wave1-carve-2026-08-23.md` for which of them Wave 1 carries and which it
 * deliberately does not.
 */
@Entity(
    tableName = "list_items",
    indices = [Index("listId"), Index("startsAt"), Index(value = ["syncId"], unique = true)],
)
data class LegacyListItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val text: String,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
    /** live-sync ticket - null until a real round trip earns one. Not client-minted (CLAUDE.md
     * live-sync ruling 5: "do not trust a client-minted id"). */
    val serverId: String? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,

    // ---- optional time trigger: an item with startsAt is a reminder, not a calendar event ----
    // Indexed - ticket 08's calendar query must never scan the untimed camping-gear rows
    // sharing this table.
    val startsAt: Long? = null,
    val endsAt: Long? = null,
    /** Only meaningful when [startsAt] is set. */
    val allDay: Boolean = true,

    // ---- optional place trigger, absorbed from PlaceReminder ----
    val triggerPlaceLabel: String? = null,

    // ---- repeat rule (ticket 04) - null repeatKind means "does not repeat" ----
    /** One of [com.kevin.legion.notes.RepeatKind]'s names, or null. */
    val repeatKind: String? = null,
    /** Daily/Weekly/MonthlyOnDate's `every` - unused (null) for Yearly, which has no interval. */
    val repeatEvery: Int? = null,
    /** Weekly only: comma-separated [java.time.DayOfWeek] names, e.g. "MONDAY,WEDNESDAY,FRIDAY". */
    val repeatDaysOfWeek: String? = null,
    /** MonthlyOnDate's day-of-month, or Yearly's day-of-month (both clamp to the month's last day). */
    val repeatDay: Int? = null,
    /** Yearly only: month, 1-12. */
    val repeatMonth: Int? = null,
    /** One of [com.kevin.legion.notes.RepeatEndKind]'s names, or null (treated as Never). */
    val repeatEndKind: String? = null,
    val repeatEndDate: Long? = null,
    val repeatEndCount: Int? = null,

    // ---- ticket 03: exact vs inexact local alarms ----
    /** True only when the DRIVER explicitly asked for a precise alarm - never set implicitly. */
    @ColumnInfo(defaultValue = "0") val exact: Boolean = false,
    /** True when [exact] was requested but `SCHEDULE_EXACT_ALARM` was refused, so the alarm was
     * silently-to-the-OS-but-NOT-to-the-driver downgraded to inexact - `notes/AlarmScheduler.kt`
     * flips this back false the moment the permission is granted and a reschedule runs. */
    @ColumnInfo(defaultValue = "0") val exactDowngraded: Boolean = false,

    // ---- ticket 12: a missed one-off reminder is reported, never silent ----
    /** Set once, by `notes/AlarmScheduler.rescheduleAll`, the first time a non-recurring item's
     * [startsAt] is found already in the past with the item still open. Null means "not missed". */
    val missedAt: Long? = null,
    /** Set when the driver dismisses this item from the MISSED list - clears the REPORT only,
     * never the item itself (firing/missing changes nothing about the underlying task, ticket 12). */
    val missedDismissedAt: Long? = null,

    // ---- v32, goal-plans ticket 08: the lazy end-of-day auto-log's idempotence anchor ----
    /** Set once, by [com.kevin.legion.advisor.GoalChecklistSync]'s end-of-day sweep, the moment a
     * TICKED past-day plan `WORKOUT` line is written through
     * [com.kevin.legion.workouts.WorkoutController.logSet]. Null means "not swept yet" - the
     * sweep's own idempotence rests entirely on this column being null exactly once per item, so a
     * repeated app-open on the same stale item can never double-log it. Deliberately NOT the same
     * signal as [doneAt]: `doneAt` is the ADHERENCE fact (the user ticked it, on whatever day that
     * happened) and survives forever per [com.kevin.legion.advisor.GoalChecklistSync]'s retention
     * rule; [loggedAt] is a bookkeeping fact about THIS app's own write path and would be wrong to
     * conflate with a tick timestamp the user might in principle produce well after ticking (a
     * missed-then-caught-up scenario). A meal/sleep checklist line is never given one - see
     * [com.kevin.legion.advisor.GoalChecklistSync]'s sweep doc for why only `WORKOUT` lines qualify. */
    @ColumnInfo(defaultValue = "NULL") val loggedAt: Long? = null,
)
