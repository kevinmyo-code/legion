package com.kevin.legion.notes

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.service.ReminderAlarmReceiver

// Cutover 1 (`docs/architecture/cutover1-2026-08-24.md`): this file used to write
// `ListItem.exactDowngraded` and read `ListItemDao.allWithTimeTrigger`/`ListItemSkipDao` directly.
// Both routes now go through `NotesController`, which is engine-backed - see that object's own
// class doc. `CarDatabase` is still imported for `listItemSkipDao()`, the one legacy table this
// wave deliberately keeps a reader on (recurrence's own per-occurrence skip state, plugin-internal,
// not migrated - `docs/architecture/wave1-carve-2026-08-23.md`).

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
     * **INCIDENT, 2026-08-26 (real device, `.scratch/backend-erp/issues/11-notes-write-path-rewire.md`).**
     * The Notes write-path cutover (`e91a296`) put this sweep on top of the Supabase replica for a
     * configured install. `EventsReconcile` never propagates an engine-side deletion to the server
     * (a separate, unruled gap - see that ticket), so a phone with 50 deleted todos still carried
     * 50 "live" rows in `events_replica`. The very first sweep on the new build walked all 50,
     * found their `startsAt` long in the past, and called [NotesController.markMissed] on every
     * one - writing 51 brand-new `missedAt` timestamps straight to Kevin's live Supabase project in
     * about two seconds. **The app told him he had missed 51 reminders he never held** - the exact
     * shape CLAUDE.md section 7's outcome-verb rule forbids for speech, now caught doing it to
     * data. [shouldSweepMarkMissed] is the guard: on the configured path this sweep schedules
     * or skips, but never asserts a miss, because it cannot tell a genuinely-overdue row from one
     * the replica is only serving back because a deletion never made it to the server. Do not
     * remove this as dead defensiveness - it is the fix for a real write that already happened.
     */
    suspend fun rescheduleAll(context: Context) {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val backendConfigured = NotesController.isBackendConfigured(context)

        for (item in NotesController.allWithTimeTrigger(context)) {
            if (item.done) continue
            val startsAt = item.startsAt ?: continue

            if (item.repeatKind == null) {
                if (startsAt < now) {
                    if (item.missedAt == null && shouldSweepMarkMissed(backendConfigured)) {
                        NotesController.markMissed(context, item.id)
                    }
                    continue
                }
                schedule(context, item, startsAt)
            } else {
                val rule = ruleFromItem(item) ?: continue
                val end = endFromItem(item)
                val skips = db.listItemSkipDao().skippedDatesForItem(item.id).toSet()
                val next = NextOccurrence.compute(startsAt, rule, end, skips, now) ?: continue
                schedule(context, item, next)
            }
        }
    }

    /**
     * Pure decision behind the incident guard documented on [rescheduleAll] - split out so the
     * rule ("never retroactively mark missed on the replica-backed path") is testable without an
     * `AlarmManager`/`PendingIntent`, neither of which this file otherwise lets a plain JVM test
     * near. `false` on the configured path is under-claiming on purpose (CLAUDE.md section 7): a
     * missed badge the app silently withholds is a far smaller harm than one it invented and wrote
     * to a server, and it is the direction to err in while `EventsReconcile`'s deletion-propagation
     * gap (the actual root cause) stays unruled.
     */
    internal fun shouldSweepMarkMissed(backendConfigured: Boolean): Boolean = !backendConfigured
}
