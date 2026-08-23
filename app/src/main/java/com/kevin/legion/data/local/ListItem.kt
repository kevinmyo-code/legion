package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line in an [ItemList]: a checklist entry, a note line (its list's `tickable == false`), or
 * - when [startsAt] is set - a **reminder** (fire a local alert at T). Charting decision 6
 * ("a calendar event is the same entity as a list item") held only until
 * `.scratch/google-account-integration/issues/04-what-happens-to-local-timed-items.md` reversed
 * it: **Google owns appointments now.** [startsAt] means "remind me at T", never "this is a
 * calendar event" - an actual appointment lives in `CalendarContract` and nowhere in this table,
 * there is no mirror row, and nothing here is ever synced against Google Calendar.
 *
 * **At most one trigger per item** ([startsAt] or [triggerPlaceLabel], never both - charting
 * decision 4). Enforced in `notes/NotesController.kt`, not a CHECK constraint, matching this
 * schema's existing no-CHECK-constraints posture (see ticket 01's answer).
 *
 * The `repeat*` columns are ticket 04's small hand-rolled rule set - explicitly NOT RFC 5545 -
 * stored as discrete columns rather than an encoded blob so a repeat is inspectable in the schema
 * and in a query. **A stored rule; occurrences are computed on read, never materialised** - see
 * [com.kevin.legion.notes.Recurrence]'s doc comment for the generator and
 * [com.kevin.legion.notes.RepeatKind]/[com.kevin.legion.notes.RepeatEndKind] for what each column
 * means. **A recurring item ([repeatKind] != null) can never be ticked** (ticket 04: "a recurring
 * item cannot be ticked" removes per-occurrence completion state entirely, and with it the "edit
 * this one or all of them" prompt) - enforced in [com.kevin.legion.notes.NotesController], not
 * here.
 *
 * v10->v11 (`.scratch/notes-lists-calendar/issues/03-*`/`12-*`) adds four columns behind local
 * alarms and fired-reminder state, none of them per-occurrence (recurrence still computes
 * occurrences on read - see [com.kevin.legion.notes.Recurrence]):
 * - [exact]/[exactDowngraded]: ticket 03's "only when the user marks an item exact, and say so in
 *   words when refused" - `exactDowngraded` is the stored words, not just a chat reply, so any
 *   later read of the item (voice or a future screen) can still see the refusal.
 * - [missedAt]/[missedDismissedAt]: ticket 12's explicit requirement that a missed ONE-OFF
 *   reminder is a STORED fact, not something recomputed after the fact - recomputing it would mean
 *   re-deriving "was this due while the phone was off" from a boot timestamp nobody kept, which is
 *   exactly the kind of silent-vanish bug this ticket was written to rule out. A recurring item is
 *   never marked missed (ticket 04: it just re-arms forward), and a place trigger never has a due
 *   time to miss (ticket 12).
 */
@Entity(
    tableName = "list_items",
    indices = [Index("listId"), Index("startsAt")],
)
data class ListItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val text: String,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val syncId: String = java.util.UUID.randomUUID().toString(),
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
