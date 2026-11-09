package com.kevin.legion.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The clock's own AlarmManager fires (ADR 0079). Direct-boot aware and not exported: only this
 * app's PendingIntents reach it. Work here is a prefs read and a service start, so no `goAsync`.
 */
class ClockFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clock = ClockGraph.controller(context)
        val id = intent.getIntExtra(EXTRA_ID, -1)
        runCatching {
            when (intent.action) {
                ACTION_ALARM -> clock.alarms.fired(id)
                ACTION_TIMER -> clock.timers.fired(id)
                ACTION_WIND_DOWN -> clock.bedtime.windDown()
                ACTION_BEDTIME -> { clock.bedtime.startNow(); clock.bedtime.rearm() }
            }
        }.onFailure { Log.e(TAG, "clock fire ${intent.action} #$id failed", it) }
    }

    companion object {
        private const val TAG = "AlfredClock"
        const val ACTION_ALARM = "com.kevin.legion.clock.ALARM"
        const val ACTION_TIMER = "com.kevin.legion.clock.TIMER"
        const val ACTION_WIND_DOWN = "com.kevin.legion.clock.WIND_DOWN"
        const val ACTION_BEDTIME = "com.kevin.legion.clock.BEDTIME"
        const val EXTRA_ID = "id"
    }
}

/**
 * The clock's notification buttons: Dismiss and Snooze on a ring, +1 min and Stop on a timer, Pause,
 * Resume and Cancel on a countdown, End bedtime. Not exported.
 */
class ClockActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clock = ClockGraph.controller(context)
        val id = intent.getIntExtra(ClockFireReceiver.EXTRA_ID, -1)
        val label = clock.timers.idLabel(id)
        runCatching {
            when (intent.action) {
                ACTION_DISMISS -> clock.alarms.dismiss(id)
                ACTION_SNOOZE -> clock.alarms.snooze(id)
                ACTION_TIMER_STOP, ACTION_TIMER_CANCEL -> clock.timers.remove(id)
                ACTION_TIMER_ADD -> clock.timers.addMinute(id)
                ACTION_TIMER_PAUSE -> clock.timers.pause(label)
                ACTION_TIMER_RESUME -> clock.timers.resume(label)
                ACTION_BEDTIME_END -> clock.bedtime.end()
            }
        }.onFailure { Log.e("AlfredClock", "clock action ${intent.action} #$id failed", it) }
    }

    companion object {
        const val ACTION_DISMISS = "com.kevin.legion.clock.DISMISS"
        const val ACTION_SNOOZE = "com.kevin.legion.clock.SNOOZE"
        const val ACTION_TIMER_STOP = "com.kevin.legion.clock.TIMER_STOP"
        const val ACTION_TIMER_ADD = "com.kevin.legion.clock.TIMER_ADD"
        const val ACTION_TIMER_PAUSE = "com.kevin.legion.clock.TIMER_PAUSE"
        const val ACTION_TIMER_RESUME = "com.kevin.legion.clock.TIMER_RESUME"
        const val ACTION_TIMER_CANCEL = "com.kevin.legion.clock.TIMER_CANCEL"
        const val ACTION_BEDTIME_END = "com.kevin.legion.clock.BEDTIME_END"
    }
}

/**
 * Re-arms the whole clock (ADR 0079). Direct-boot aware, exported because the system sends these:
 * `LOCKED_BOOT_COMPLETED` (before the first unlock), `BOOT_COMPLETED`, `TIME_SET`,
 * `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED` and the exact-alarm grant changing. Each is either
 * protected or harmless to receive twice: [ClockController.rearmAll] is idempotent.
 */
class ClockRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        runCatching { ClockGraph.controller(context).rearmAll() }
            .onFailure { Log.e("AlfredClock", "clock re-arm on ${intent.action} failed", it) }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
