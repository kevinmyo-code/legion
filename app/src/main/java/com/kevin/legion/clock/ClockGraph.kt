package com.kevin.legion.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.kevin.legion.ui.clock.ClockActivity
import java.time.ZoneId

/**
 * The process's one [ClockController] (ADR 0079). Built from the APPLICATION context and
 * device-protected storage only, so it is safe before the first unlock - nothing here may touch
 * credential-encrypted storage, `MidnightApplication`'s graph or the network.
 */
object ClockGraph {
    @Volatile private var instance: ClockController? = null

    /** Test seam: a controller over fakes. */
    internal var factory: ((Context) -> ClockController)? = null

    fun controller(context: Context): ClockController {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: (factory?.invoke(context) ?: build(context.applicationContext)).also { instance = it }
        }
    }

    /** Tests only: drop the singleton. */
    internal fun reset() { instance = null }

    private fun build(app: Context): ClockController {
        val notifier = ClockNotifier(app)
        return ClockController(
            store = PrefsClockStore.forContext(app),
            time = SystemClockTime(app),
            scheduler = AndroidClockScheduler(app),
            notices = notifier,
            ringer = AndroidRinger(app),
            effects = AndroidBedtimeEffects(app),
        )
    }
}

class SystemClockTime(private val context: Context) : ClockTime {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun bootCount(): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)
}

/**
 * AlarmManager for the clock. Alarms use `setAlarmClock` (status-bar icon, Doze-exempt); timers and
 * bedtime use `setExactAndAllowWhileIdle`. Exact needs `USE_EXACT_ALARM` (granted at install, ADR
 * 0079) or `SCHEDULE_EXACT_ALARM` on Android 12; without either it degrades to inexact and the Clock
 * screen says so ([ClockHealth]).
 */
class AndroidClockScheduler(private val context: Context) : ClockScheduler {
    private val am: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun armAlarm(id: Int, atMs: Long) {
        val fire = fire(ClockFireReceiver.ACTION_ALARM, id)
        if (exactAllowed()) {
            val show = PendingIntent.getActivity(
                context, id, ClockActivity.intent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, show), fire)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, fire)
        }
    }

    override fun cancelAlarm(id: Int) = am.cancel(fire(ClockFireReceiver.ACTION_ALARM, id))

    override fun armTimer(id: Int, atElapsed: Long) =
        exact(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, fire(ClockFireReceiver.ACTION_TIMER, id))

    override fun cancelTimer(id: Int) = am.cancel(fire(ClockFireReceiver.ACTION_TIMER, id))

    override fun armBedtime(windDownAtMs: Long?, startAtMs: Long) {
        val windDown = fire(ClockFireReceiver.ACTION_WIND_DOWN, 0)
        if (windDownAtMs == null) am.cancel(windDown) else exact(AlarmManager.RTC_WAKEUP, windDownAtMs, windDown)
        exact(AlarmManager.RTC_WAKEUP, startAtMs, fire(ClockFireReceiver.ACTION_BEDTIME, 0))
    }

    override fun cancelBedtime() {
        am.cancel(fire(ClockFireReceiver.ACTION_WIND_DOWN, 0))
        am.cancel(fire(ClockFireReceiver.ACTION_BEDTIME, 0))
    }

    private fun exact(type: Int, at: Long, pi: PendingIntent) {
        if (exactAllowed()) am.setExactAndAllowWhileIdle(type, at, pi) else am.setAndAllowWhileIdle(type, at, pi)
    }

    private fun exactAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /** One PendingIntent per (action, id): the data URI is what makes them distinct. */
    private fun fire(action: String, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ClockFireReceiver::class.java).setAction(action)
            .setData(Uri.parse("legion-clock://$action/$id"))
            .putExtra(ClockFireReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
