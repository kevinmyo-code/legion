package com.kevin.legion.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.kevin.legion.ui.clock.ClockActivity

/**
 * The clock's notifications other than the ring's own: timer countdowns, missed alarms, wind-down,
 * and the ongoing bedtime. Every post is guarded: with notifications refused nothing shows, and the
 * Clock screen says so ([ClockHealth]).
 */
class ClockNotifier(private val context: Context) : ClockNotices {
    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    override fun timer(timer: ClockTimer) {
        ensureChannels(context)
        val running = timer.state == TimerState.RUNNING
        val left = AlarmMath.timerRemaining(timer, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis(),
            SystemClockTime(context).bootCount())
        val b = NotificationCompat.Builder(context, CHANNEL_TIMERS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(timer.label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setContentIntent(open(context, ClockActivity.TAB_TIMERS))
        if (running) {
            b.setWhen(System.currentTimeMillis() + left).setUsesChronometer(true).setChronometerCountDown(true)
                .setShowWhen(true).setContentText("Timer running")
                .addAction(0, "Pause", action(context, ClockActionReceiver.ACTION_TIMER_PAUSE, timer.id))
        } else {
            b.setShowWhen(false).setContentText("Paused, ${AlarmMath.digital(left)} left")
                .addAction(0, "Resume", action(context, ClockActionReceiver.ACTION_TIMER_RESUME, timer.id))
        }
        b.addAction(0, "Cancel", action(context, ClockActionReceiver.ACTION_TIMER_CANCEL, timer.id))
        post(TIMER_BASE + timer.id, b.build())
    }

    override fun timerGone(id: Int) = nm.cancel(TIMER_BASE + id)

    override fun missedAlarm(alarm: ClockAlarm) {
        ensureChannels(context)
        post(MISSED_BASE + alarm.id, info("Missed alarm", "${AlarmText.describe(alarm)} rang out unanswered.").build())
    }

    override fun windDown(bedtime: Bedtime, nextAlarm: String?) {
        ensureChannels(context)
        val alarm = nextAlarm?.let { "Next alarm: $it." } ?: "No alarm is set."
        val at = AlarmMath.hhmm(bedtime.hour, bedtime.minute)
        val text = "Bedtime at $at, in ${bedtime.windDownMin} minutes. $alarm"
        post(WIND_DOWN_ID, info("Time to wind down", text).build())
    }

    override fun bedtimeOn(sentence: String) {
        ensureChannels(context)
        nm.cancel(WIND_DOWN_ID)
        val n = info("Bedtime", sentence)
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .addAction(0, "End bedtime", action(context, ClockActionReceiver.ACTION_BEDTIME_END, 0))
            .setContentIntent(open(context, ClockActivity.TAB_BEDTIME))
            .build()
        post(BEDTIME_ID, n)
    }

    override fun bedtimeGone() = nm.cancel(BEDTIME_ID)

    private fun info(title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_INFO)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, ClockActivity.TAB_ALARMS))

    private fun post(id: Int, n: Notification) {
        runCatching { nm.notify(id, n) }.onFailure { Log.w("AlfredClock", "could not post clock notice $id", it) }
    }

    companion object {
        const val CHANNEL_RING = "clock_ring"
        const val CHANNEL_TIMERS = "clock_timers"
        const val CHANNEL_INFO = "clock_info"
        const val RING_ID = 7_900
        const val WIND_DOWN_ID = 7_901
        const val BEDTIME_ID = 7_902
        private const val TIMER_BASE = 8_000
        private const val MISSED_BASE = 9_000

        /**
         * Three channels. The ring channel is HIGH (heads-up, full-screen) and silent, because the
         * ring service plays the sound itself on the alarm stream with a ramp.
         */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java)
            val ring = NotificationChannel(CHANNEL_RING, "Alarms ringing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An alarm or timer going off. Shows over the lock screen."
                setSound(null, null)
                enableVibration(false)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val timers = NotificationChannel(CHANNEL_TIMERS, "Timers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Running timers, with Pause and Cancel."
                setSound(null, null)
            }
            val notices = NotificationManager.IMPORTANCE_DEFAULT
            val info = NotificationChannel(CHANNEL_INFO, "Clock notices", notices).apply {
                description = "Missed alarms, wind-down and bedtime."
            }
            nm.createNotificationChannels(listOf(ring, timers, info))
        }

        fun action(context: Context, action: String, id: Int): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ClockActionReceiver::class.java).setAction(action)
                .setData(Uri.parse("legion-clock-action://$action/$id"))
                .putExtra(ClockFireReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun open(context: Context, tab: Int): PendingIntent = PendingIntent.getActivity(
            context,
            tab,
            ClockActivity.intent(context, tab),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
