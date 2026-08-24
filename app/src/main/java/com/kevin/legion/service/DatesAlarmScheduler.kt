package com.kevin.legion.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kevin.legion.engine.dates.DatesAgenda

/**
 * Owns the ONE `AlarmManager` alarm the Dates aspect ever has armed at a time
 * (`.scratch/aspect-engine/issues/19-build-dates-aspect.md` point 3: "an AlarmScheduler that arms
 * the NEXT due reminder from the agenda query" - singular, unlike `notes/AlarmScheduler`'s
 * one-alarm-per-item design, which this file deliberately does not extend since the notes/lists
 * domain and the engine's Dates aspect are separate stores with separate reminder shapes).
 *
 * [armNext] is idempotent and safe to call from any of its callers back to back - app start
 * ([com.kevin.legion.MidnightApplication]), reboot ([BootReceiver]), the exact-alarm-permission
 * state-changed broadcast ([ExactAlarmPermissionReceiver]), and [DatesReminderAlarmReceiver] itself
 * right after firing - because it always re-derives the single next candidate fresh from
 * [DatesAgenda.nextUnmuted] rather than trusting any cached state, the same "re-derive, never
 * trust a cache" posture `notes/AlarmScheduler.rescheduleAll`'s own doc comment already documents.
 *
 * Exact-alarm mechanics mirror `notes/AlarmScheduler`'s own answer
 * (`.scratch/notes-lists-calendar/issues/03-android-alarm-mechanism.md`, ticket 19 point 4's
 * "exact alarms with the permission flow"): `setExactAndAllowWhileIdle` when
 * `canScheduleExactAlarms()` allows it, `setAndAllowWhileIdle` (inexact, permission-free,
 * Doze-exempt) otherwise - a silent downgrade, never a dropped reminder. `SCHEDULE_EXACT_ALARM` is
 * already declared in `AndroidManifest.xml` (notes-lists-calendar ticket 03) and requested via the
 * same `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` settings-intent flow that domain already built; this
 * file reuses that grant rather than requesting its own copy of the same OS permission.
 */
object DatesAlarmScheduler {
    /** One fixed request code - only one Dates alarm/PendingIntent is ever outstanding, so
     * `FLAG_UPDATE_CURRENT` always replaces it in place rather than stacking a second one. */
    private const val REQUEST_CODE = 90001

    private fun alarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** True below API 31 unconditionally - the deny-by-default `SCHEDULE_EXACT_ALARM` gate is an
     * Android 12+ behaviour, matching `notes/AlarmScheduler.canScheduleExact`'s own finding. */
    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager(context).canScheduleExactAlarms() else true

    private fun pendingIntent(context: Context, recordId: Long?): PendingIntent {
        val intent = Intent(context, DatesReminderAlarmReceiver::class.java).apply {
            action = DatesReminderAlarmReceiver.ACTION_FIRE
            if (recordId != null) putExtra(DatesReminderAlarmReceiver.EXTRA_RECORD_ID, recordId)
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Cancels whatever is currently armed - idempotent, safe to call with nothing pending. */
    fun cancel(context: Context) {
        alarmManager(context).cancel(pendingIntent(context, recordId = null))
    }

    /**
     * Re-derives the single soonest unmuted due record ([DatesAgenda.nextUnmuted]) and arms
     * exactly one alarm for it, replacing whatever was armed before. Cancels outright when nothing
     * qualifies, so a cleared agenda never leaves a stale alarm pointed at a record that no longer
     * exists or is now muted.
     */
    suspend fun armNext(context: Context, now: Long = System.currentTimeMillis()) {
        val next = DatesAgenda.nextUnmuted(context, now)
        if (next == null) {
            cancel(context)
            return
        }
        val am = alarmManager(context)
        val pi = pendingIntent(context, next.recordId)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.dueAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.dueAt, pi)
        }
    }
}
