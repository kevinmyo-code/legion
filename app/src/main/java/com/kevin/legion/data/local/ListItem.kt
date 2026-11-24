package com.kevin.legion.data.local

/**
 * One line of the notes model as the rest of the app holds it in memory: a to-do, a note line, or
 * - when [startsAt] is set - a **reminder** (fire a local alert at T).
 *
 * **A plain data class, not a Room entity** (lists redesign slice 1a, 2026-11-24). It used to be
 * both: the `@Entity` for the legacy `list_items` table AND the shape
 * `notes/NotesController.kt` returns while reading `events` (its `Event.toListItem` mapper). One
 * name over two unrelated stores meant a signature could not say which one a value had come from,
 * and the two meanings had already drifted - [syncId] is the server row's own uuid on the
 * configured path and a client-minted placeholder on the unconfigured one, and [listId] is a
 * display convenience here where it was a real foreign key in the table. The table's row is
 * [LegacyListItem] now; this is the domain type, with one meaning.
 *
 * **Where its values come from.** [NotesController][com.kevin.legion.notes.NotesController] builds
 * every one of these from an [Event] row (see its `Event.toListItem` mapper for the field-by-field
 * account and for what [syncId] means on each transport). Nothing reads `list_items` to make one.
 * [id] is the `Event`'s id, preserved and never reminted.
 *
 * Charting decision 6 ("a calendar event is the same entity as a list item") held only until
 * `.scratch/google-account-integration/issues/04-what-happens-to-local-timed-items.md` reversed
 * it, and the 2026-08-25 ruling ("one calendar, and it is ours") then retired Google entirely:
 * **appointments live in LEGION's own `events` table.** [startsAt] means "remind me at T", never
 * "this is a calendar event", and nothing in the app reads or writes `CalendarContract`.
 *
 * **At most one trigger per item** ([startsAt] or [triggerPlaceLabel], never both - charting
 * decision 4), enforced in `notes/NotesController.kt`. **A recurring item ([repeatKind] != null)
 * can never be ticked** (ticket 04: that removes per-occurrence completion state entirely, and
 * with it the "edit this one or all of them" prompt), enforced in the same place. The `repeat*`
 * fields are ticket 04's small hand-rolled rule set - explicitly NOT RFC 5545 - held as discrete
 * fields rather than an encoded blob so a repeat is inspectable in a query; see
 * [com.kevin.legion.notes.Recurrence]'s doc comment for the generator, which computes occurrences
 * on read and never materialises them, and
 * [com.kevin.legion.notes.RepeatKind]/[com.kevin.legion.notes.RepeatEndKind] for what each one
 * means. The one piece of per-occurrence state that does exist is [ListItemSkip].
 */
data class ListItem(
    val id: Long = 0,
    val listId: Long,
    val text: String,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val syncId: String = java.util.UUID.randomUUID().toString(),
    /** live-sync ticket - null until a real round trip earns one. Not client-minted (CLAUDE.md
     * live-sync ruling 5: "do not trust a client-minted id"). */
    val serverId: String? = null,
    val deleted: Boolean = false,

    // ---- optional time trigger: an item with startsAt is a reminder, not a calendar event ----
    val startsAt: Long? = null,
    val endsAt: Long? = null,
    /** Only meaningful when [startsAt] is set. */
    val allDay: Boolean = true,

    // ---- optional place trigger (absorbed from the legacy `place_reminders` table by
    // MIGRATION_9_10, long before this type stopped being that table's row) ----
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
    val exact: Boolean = false,
    /** True when [exact] was requested but `SCHEDULE_EXACT_ALARM` was refused, so the alarm was
     * silently-to-the-OS-but-NOT-to-the-driver downgraded to inexact - `notes/AlarmScheduler.kt`
     * flips this back false the moment the permission is granted and a reschedule runs. */
    val exactDowngraded: Boolean = false,

    // ---- ticket 12: a missed one-off reminder is reported, never silent ----
    /** Set once, by `notes/AlarmScheduler.rescheduleAll`, the first time a non-recurring item's
     * [startsAt] is found already in the past with the item still open. Null means "not missed". */
    val missedAt: Long? = null,
    /** Set when the driver dismisses this item from the MISSED list - clears the REPORT only,
     * never the item itself (firing/missing changes nothing about the underlying task, ticket 12). */
    val missedDismissedAt: Long? = null,

    // ---- goal-plans ticket 08: the lazy end-of-day auto-log's idempotence anchor ----
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
    val loggedAt: Long? = null,
)
