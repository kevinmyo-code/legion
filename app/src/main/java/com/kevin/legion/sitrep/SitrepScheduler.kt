package com.kevin.legion.sitrep

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId

/**
 * Owns the ONE `AlarmManager` alarm the scheduled sitrep uses (ticket 22 part D). There is never
 * more than one - unlike [com.kevin.legion.notes.AlarmScheduler], which arms a `PendingIntent` per
 * reminder item, the sitrep has exactly one configurable time, so [REQUEST_CODE] is a fixed
 * constant rather than a per-item id.
 *
 * **`setAndAllowWhileIdle`, never `setExact*`, and never `setRepeating`** - the same two rules
 * [com.kevin.legion.notes.AlarmScheduler]'s own class doc states verbatim ("re-arm on fire, never
 * `setRepeating`... imprecise since API 19"). A daily digest has no reason to demand
 * millisecond-exact delivery (unlike a reminder the user set for a specific moment), so this never
 * even asks for `SCHEDULE_EXACT_ALARM` - one fewer permission this feature needs at all. Re-arming
 * happens in [SitrepAlarmReceiver] the moment the alarm fires, computing tomorrow's occurrence
 * fresh rather than trusting a repeating alarm to survive Doze, a reboot, or drift.
 *
 * `.scratch/proactive-mode/issues/07-scheduling-research.md`'s finding that a Samsung-restricted
 * app reliably gets **one** alarm a day is exactly this feature's shape - one alarm, once a day -
 * so nothing here fights that ceiling.
 */
object SitrepScheduler {
    /** Fixed request code: the sitrep alarm is a singleton, never per-item. Arbitrary but stable -
     * changing it would leak a dangling alarm under the old code the next time [cancel] runs. */
    private const val REQUEST_CODE = 90210

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SitrepAlarmReceiver::class.java).apply {
            action = SitrepAlarmReceiver.ACTION_FIRE
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Cancels the sitrep alarm, if one is armed. Safe to call when none is - a no-op then, same
     * as [com.kevin.legion.notes.AlarmScheduler.cancel]. Called when Kevin turns the schedule off
     * on the settings screen. */
    fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context))
    }

    /**
     * The next epoch-millis moment [hour]:[minute] occurs, in [zone] - today if that time has not
     * yet passed relative to [now], tomorrow otherwise. `internal` for direct unit testing; pure
     * date arithmetic, no `Context`.
     */
    internal fun nextTriggerAt(hour: Int, minute: Int, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val nowZdt = Instant.ofEpochMilli(now).atZone(zone)
        var candidate = nowZdt.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!candidate.isAfter(nowZdt)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    /** Arms (or re-arms, replacing any existing one - same `PendingIntent`, `FLAG_UPDATE_CURRENT`)
     * the sitrep alarm for the next occurrence of [hour]:[minute]. */
    fun schedule(context: Context, hour: Int, minute: Int, now: Long = System.currentTimeMillis()) {
        val triggerAt = nextTriggerAt(hour, minute, now)
        alarmManager(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
    }

    /**
     * Re-arms from whatever is persisted in [SitrepSettings], or does nothing if Kevin has never
     * set a schedule. Called from exactly two places, mirroring
     * [com.kevin.legion.notes.AlarmScheduler.rescheduleAll]'s own two non-firing callers:
     * [com.kevin.legion.service.BootReceiver] (alarms do not survive a reboot) and the settings
     * screen, the moment a new time is saved.
     */
    suspend fun rescheduleFromSettings(context: Context) {
        val schedule = SitrepSettings.schedule(context) ?: return
        schedule(context, schedule.hour, schedule.minute)
    }
}
