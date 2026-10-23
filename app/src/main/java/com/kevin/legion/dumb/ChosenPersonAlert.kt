package com.kevin.legion.dumb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** How a chosen person's message is made audible while Do Not Disturb is on. */
enum class AlertMode {
    /** A notification channel with `setBypassDnd(true)` and its own sound. */
    CHANNEL_BYPASS,

    /** A silent notification, plus vibration and the notification sound played on the ALARM stream. */
    ALARM_STREAM_FALLBACK,
    ;

    companion object {
        /**
         * The single switch (ADR 0062): [forceFallback] is the user's setting; the fallback is also
         * chosen when the system will not let the channel bypass Do Not Disturb ([canBypass] false),
         * which is how a vendor that ignores the flag shows up.
         */
        fun choose(forceFallback: Boolean, canBypass: Boolean): AlertMode =
            if (forceFallback || !canBypass) ALARM_STREAM_FALLBACK else CHANNEL_BYPASS
    }
}

/** The "use the alarm-stream fallback" switch, kept in SharedPreferences. Default off: try the bypass channel. */
class AlertFallbackSwitch(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    var forced: Boolean
        get() = prefs.getBoolean(KEY, false)
        set(value) {
            prefs.edit().putBoolean(KEY, value).apply()
        }

    private companion object {
        const val PREFS = "dumb_mode_alert"
        const val KEY = "force_alarm_stream_fallback"
    }
}

/**
 * Re-alerts a chosen person's message (ADR 0062). Their own notification stays in the shade, silent
 * under the Priority policy; this posts a short second one that is not. Its text is the chosen
 * person's name (Kevin's own contact data) and the app's label: **no word of the message is copied**
 * (CLAUDE.md section 7). Tapping it fires the original notification's own content intent.
 *
 * Which of the two [AlertMode]s runs is decided per alert by [AlertMode.choose], reading the live
 * channel, so a phone that ignores bypass falls back without a settings visit.
 */
class ChosenPersonAlert(context: Context, private val switch: AlertFallbackSwitch) {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)

    fun alert(personName: String, appLabel: String, key: String, tap: PendingIntent?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mode = AlertMode.choose(switch.forced, bypassPermitted())
        val channel = if (mode == AlertMode.CHANNEL_BYPASS) BYPASS_CHANNEL else SILENT_CHANNEL
        val notification = NotificationCompat.Builder(app, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(personName)
            .setContentText("New message in $appLabel")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .apply { tap?.let { setContentIntent(it) } }
            .build()
        NotificationManagerCompat.from(app).notify(ID_BASE + (key.hashCode() and ID_MASK), notification)
        if (mode == AlertMode.ALARM_STREAM_FALLBACK) ringOnAlarmStream()
    }

    /** Creates both channels (idempotent) and reports whether the system kept the bypass flag. */
    private fun bypassPermitted(): Boolean {
        val bypass = NotificationChannel(BYPASS_CHANNEL, "Chosen people", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A message from someone you chose, while matrix mode is on."
            setBypassDnd(true)
        }
        val silent = NotificationChannel(
            SILENT_CHANNEL,
            "Chosen people (quiet)",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Used when this phone will not let a channel break through Do Not Disturb."
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(bypass)
        nm.createNotificationChannel(silent)
        return nm.getNotificationChannel(BYPASS_CHANNEL)?.canBypassDnd() == true
    }

    @Suppress("DEPRECATION") // deprecated in 33, still the only usage-carrying overload on 26..32
    private fun ringOnAlarmStream() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching {
            RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
                ?.apply { setAudioAttributes(attrs) }
                ?.play()
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                app.getSystemService(Vibrator::class.java)
            }
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATE_PATTERN, NO_REPEAT), attrs)
        }
    }

    private companion object {
        const val BYPASS_CHANNEL = "dumb_chosen_bypass"
        const val SILENT_CHANNEL = "dumb_chosen_quiet"
        const val TIMEOUT_MS = 120_000L
        const val ID_BASE = 0x2D000000
        const val ID_MASK = 0x00FFFFFF
        const val NO_REPEAT = -1
        val VIBRATE_PATTERN = longArrayOf(0, 250, 150, 250)
    }
}
