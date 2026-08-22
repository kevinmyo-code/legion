package com.kevin.legion.wellbeing

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId

/**
 * Owns the ONE `AlarmManager` alarm the wellbeing digest uses - goal-plans ticket 05, same shape
 * [com.kevin.legion.sitrep.SitrepScheduler] uses for its own single configurable-time alarm, and
 * this object is modelled on that one directly rather than generalising a shared scheduler for two
 * callers, matching that file's own reasoning: there is never more than one digest alarm, so
 * [REQUEST_CODE] is a fixed constant, not a per-item id.
 *
 * **`setAndAllowWhileIdle`, never `setExact*`, and never `setRepeating`** - unchanged reasoning
 * from [com.kevin.legion.sitrep.SitrepScheduler]'s own class doc: a daily digest has no reason to
 * demand millisecond-exact delivery, so this never asks for `SCHEDULE_EXACT_ALARM`. Re-arming
 * happens in [WellbeingDigestAlarmReceiver] the moment the alarm fires, computing tomorrow's
 * occurrence fresh rather than trusting a repeating alarm to survive Doze, a reboot, or drift.
 */
object WellbeingDigestScheduler {
    /** Fixed request code: the wellbeing digest alarm is a singleton, never per-item. Arbitrary
     * but stable, and DISTINCT from [com.kevin.legion.sitrep.SitrepScheduler]'s own
     * `REQUEST_CODE` - two schedulers sharing one code would silently replace each other's alarm. */
    private const val REQUEST_CODE = 90211

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WellbeingDigestAlarmReceiver::class.java).apply {
            action = WellbeingDigestAlarmReceiver.ACTION_FIRE
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Cancels the wellbeing digest alarm, if one is armed. Safe to call when none is - a no-op
     * then, same as [com.kevin.legion.sitrep.SitrepScheduler.cancel]. */
    fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context))
    }

    /** The next epoch-millis moment [hour]:[minute] occurs, in [zone] - today if that time has not
     * yet passed relative to [now], tomorrow otherwise. Identical arithmetic to
     * [com.kevin.legion.sitrep.SitrepScheduler.nextTriggerAt]; kept as a separate `internal` copy
     * rather than a shared helper because the two callers' request codes and receivers must never
     * be confused with each other, and a shared utility invites exactly that. `internal` for direct
     * unit testing; pure date arithmetic, no `Context`. */
    internal fun nextTriggerAt(hour: Int, minute: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val nowZdt = Instant.ofEpochMilli(now).atZone(zone)
        var candidate = nowZdt.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(nowZdt)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    /** Arms (or re-arms, replacing any existing one - same `PendingIntent`, `FLAG_UPDATE_CURRENT`)
     * the wellbeing digest alarm for the next occurrence of [hour]:[minute]. */
    fun schedule(context: Context, hour: Int, minute: Int, now: Long = System.currentTimeMillis()) {
        val triggerAt = nextTriggerAt(hour, minute, now)
        alarmManager(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
    }

    /** Re-arms from whatever is persisted in [WellbeingDigestSettings], or does nothing if Kevin
     * has never set a schedule. Called from the same two places
     * [com.kevin.legion.sitrep.SitrepScheduler.rescheduleFromSettings] is:
     * [com.kevin.legion.service.BootReceiver] (alarms do not survive a reboot) and the settings
     * screen, the moment a new time is saved. */
    suspend fun rescheduleFromSettings(context: Context) {
        val schedule = WellbeingDigestSettings.schedule(context) ?: return
        schedule(context, schedule.hour, schedule.minute)
    }
}
