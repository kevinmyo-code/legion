package com.kevin.legion.notes

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.service.ReminderAlarmReceiver

// Cutover 1 (`docs/architecture/cutover1-2026-08-24.md`): this file used to write
// `ListItem.exactDowngraded` and read `ListItemDao.allWithTimeTrigger`/`ListItemSkipDao` directly.
// Both routes now go through `NotesController`, which is engine-backed - see that object's own
// class doc. **Corrected 2026-08-27 (ticket 15 step 4):** this comment used to say CarDatabase was
// still imported for a direct `listItemSkipDao()` read. It no longer is. That direct read was a
// real bug once the ticket 11 rewrite branched skip storage - a configured install keeps skips in
// `event_skips` keyed on the server id, so reading the legacy DAO here returned an empty set and
// every occurrence the user had explicitly skipped fired anyway. Skips now go through
// `NotesController.skippedDates`, which branches, and this file holds no DAO reference at all.

/**
 * Owns every `AlarmManager` interaction for the notes/lists/calendar domain -
 * `.scratch/notes-lists-calendar/issues/03-android-alarm-mechanism.md`'s Answer, verbatim:
 *
 * - **Default for every reminder: `setAndAllowWhileIdle`** - inexact, permission-free, Doze-exempt.
 * - **`setExactAndAllowWhileIdle` ONLY when the item is marked exact**, gated on
 *   `canScheduleExactAlarms()`. Refused -> downgrade to inexact and persist [ListItem.exactDowngraded]
 *   so the refusal is said in words on the item, never silently.
 * - **One idempotent [rescheduleAll]**, called from exactly three places: app start
 *   ([com.kevin.legion.MidnightApplication]), reboot ([com.kevin.legion.service.BootReceiver]), and
 *   the exact-alarm-permission-state-changed broadcast
 *   ([com.kevin.legion.service.ExactAlarmPermissionReceiver]).
 *
 * No `setRepeating`/`setInexactRepeating` anywhere in this file - ticket 04's "re-arm on fire,
 * never `setRepeating`" (imprecise since API 19, and `setInexactRepeating` can't express "weekdays"
 * or "monthly on the 3rd" at all). [rescheduleAll] and
 * [com.kevin.legion.service.ReminderAlarmReceiver] (on fire) are the only two callers of [schedule]
 * for a recurring item, and both always compute a single NEXT occurrence via [NextOccurrence]
 * rather than ever arming more than one alarm per item.
 */
object AlarmScheduler {
    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * True if this device can grant `SCHEDULE_EXACT_ALARM` right now. Always true below API 31 -
     * the ticket's whole finding is that the deny-by-default gate is an Android 12+/targetSdk 34
     * behaviour, so there is nothing to check on an older platform version.
     */
    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager(context).canScheduleExactAlarms() else true

    /** One `PendingIntent` per item id (`FLAG_UPDATE_CURRENT`), so re-scheduling the same item
     * replaces its alarm in place rather than stacking a second one - what makes [rescheduleAll]
     * safe to call repeatedly. */
    private fun pendingIntentFor(context: Context, itemId: Long): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_FIRE
            putExtra(ReminderAlarmReceiver.EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context, itemId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Cancels any pending alarm for [itemId] - idempotent, safe on an item with nothing scheduled
     * (removing a trigger, ticking an item, or deleting it all route here). */
    fun cancel(context: Context, itemId: Long) {
        alarmManager(context).cancel(pendingIntentFor(context, itemId))
    }

    /**
     * Schedules exactly one alarm for [item] at [triggerAt]. [item].exact decides which
     * `AlarmManager` call is used; when exact is requested but refused, this both falls back to
     * `setAndAllowWhileIdle` AND persists [ListItem.exactDowngraded] = true (and the reverse, the
     * moment permission comes back, so a later grant clears the stale downgrade notice on its
     * own the next time [rescheduleAll] runs).
     */
    suspend fun schedule(context: Context, item: ListItem, triggerAt: Long) {
        val am = alarmManager(context)
        val pi = pendingIntentFor(context, item.id)
        val canExact = canScheduleExact(context)

        if (item.exact && canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            if (item.exactDowngraded) NotesController.setExactDowngraded(context, item.id, false)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            val shouldBeDowngraded = item.exact && !canExact
            if (item.exactDowngraded != shouldBeDowngraded) NotesController.setExactDowngraded(context, item.id, shouldBeDowngraded)
        }
    }

    /**
     * The one idempotent entry point (ticket 03). Walks every non-deleted item with a time
     * trigger and either (a) schedules its next alarm, or (b) - for a non-recurring item whose
     * [ListItem.startsAt] has already passed - marks it MISSED (ticket 12) and schedules nothing,
     * since a one-off that already happened will never fire. A recurring item is never "missed" in
     * that sense: [NextOccurrence.compute] always looks forward from `now`, so a chain broken by a
     * powered-off phone silently re-arms on the next real occurrence rather than trying (and
     * failing) to resume from whatever it last fired - ticket 04's sharp edge, closed here.
     *
     * Safe to call from all three of its callers back to back: nothing here depends on which
     * caller invoked it, and re-scheduling an already-correctly-scheduled item is a no-op cost
     * (same `PendingIntent`, same target time, `AlarmManager` just replaces it with itself).
     *
     * **INCIDENT, 2026-08-26 (real device, `.scratch/backend-erp/issues/11-notes-write-path-rewire.md`),
     * FIXED FOR REAL 2026-08-27, not just guarded.** The Notes write-path cutover (`e91a296`) put
     * this sweep on top of the Supabase replica for a configured install. Two things were wrong
     * with what it read, and both are now fixed at the read, not worked around here:
     * 1. `EventsReconcile` never propagated an engine-side deletion to the server, so a phone with
     *    50 deleted todos still carried 50 "live" rows in `events_replica` - fixed by ticket 11's
     *    2026-08-27 ruling #2 (`EventsReconcile.run`'s retraction pass: a server row whose
     *    `origin_guid` names a trashed-or-absent engine record is soft-deleted).
     * 2. `NotesController`'s read was unfiltered over `events_replica`, which also holds every
     *    Dates `Event`/Google import merged into the same table - so a genuine appointment read
     *    back as a reminder this sweep owned. Fixed by ticket 11's ruling #1 (`events.kind`;
     *    [NotesController.allWithTimeTrigger] now only ever returns a `reminder`).
     * The very first sweep on the pre-fix build walked all 50 deleted todos plus every calendar
     * appointment, found their `startsAt` long in the past, and called [NotesController.markMissed]
     * on every one - writing 51 brand-new `missedAt` timestamps straight to Kevin's live Supabase
     * project in about two seconds, the exact shape CLAUDE.md section 7's outcome-verb rule
     * forbids for speech, caught doing it to data. **The stopgap guard this doc comment used to
     * describe (`shouldSweepMarkMissed`, withholding every missed-mark on a configured install) is
     * REMOVED in the same commit that lands the two fixes above** - Kevin's own ruling: "a guard
     * left in place over a fixed read is dead code that someone eventually deletes without knowing
     * what it was for." The read [NotesController.allWithTimeTrigger] now hands this sweep is
     * correct by construction (reminder-only, and nothing the phone deleted lingers), so a real
     * overdue reminder is marked missed on every install again, configured or not.
     */
    suspend fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()

        for (item in NotesController.allWithTimeTrigger(context)) {
            if (item.done) continue
            val startsAt = item.startsAt ?: continue

            if (item.repeatKind == null) {
                if (startsAt < now) {
                    if (item.missedAt == null) {
                        NotesController.markMissed(context, item.id)
                    }
                    continue
                }
                schedule(context, item, startsAt)
            } else {
                val rule = ruleFromItem(item) ?: continue
                val end = endFromItem(item)
                // Through NotesController, never the DAO directly. `skippedDates` BRANCHES:
                // configured installs keep skips in `event_skips` keyed on the server id, while
                // unconfigured ones keep them in `list_item_skips` keyed on the local id. Reading
                // the legacy DAO here returned an EMPTY set on a configured install, so every
                // occurrence the user had explicitly skipped came back and fired an alarm anyway.
                // Introduced by the ticket 11 rewrite, which branched the read in NotesController
                // and left this call site pointing at the old table; found while tracing step 4.
                val skips = NotesController.skippedDates(context, item)
                val next = NextOccurrence.compute(startsAt, rule, end, skips, now) ?: continue
                schedule(context, item, next)
            }
        }
    }
}
