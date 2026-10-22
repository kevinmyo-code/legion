package com.kevin.legion.freshness

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** [ExpirySeenStore] on SharedPreferences (install-scoped, never synced). */
class PrefsExpirySeenStore(context: Context) : ExpirySeenStore {
    private val prefs = LoginExpirySettings.prefs(context)

    override fun load(): Map<String, String> =
        prefs.all.filterKeys { it.startsWith(SEEN_PREFIX) }
            .mapKeys { it.key.removePrefix(SEEN_PREFIX) }
            .mapValues { it.value as? String ?: "" }

    override fun save(seen: Map<String, String>) {
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(SEEN_PREFIX) }.forEach { remove(it) }
            seen.forEach { (source, key) -> putString(SEEN_PREFIX + source, key) }
        }.apply()
    }

    private companion object {
        const val SEEN_PREFIX = "seen_"
    }
}

/** The durable "stop these" flag. Only the settings row turns it back off. */
object LoginExpirySettings {
    private const val PREFS = "login_expiry_notices"
    private const val KEY_SILENCED = "silenced"

    internal fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun silenced(context: Context): Boolean = prefs(context).getBoolean(KEY_SILENCED, false)

    fun setSilenced(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_SILENCED, on).apply()
    }
}

/**
 * The notification, its channel, and its one-tap "Stop these" action. The body is the server's
 * sentence untouched. Compulsion test (CLAUDE.md section 7): (a) anchored to the server's own
 * refusal of the login, (b) actionable now (the sentence names the command), (c) never speaks of
 * absence or engagement, (d) [LoginExpiryStopReceiver] silences it forever in one tap.
 *
 * Not registered with `ProactiveBus`/`ProactiveCategory`: that registry governs SPOKEN raises
 * (master switch, quiet hours, daily cap) and this never speaks, so routing it there would
 * subject a plain notification to a spoken-line cap it has no reason to share.
 */
class LoginExpiryNotifier(context: Context) {
    private val app = context.applicationContext

    fun raise(expiry: LoginExpiry) {
        if (!mayPost()) return
        ensureChannel()
        val id = notificationId(expiry.source)
        val open = PendingIntent.getActivity(
            app, id,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            app, id,
            Intent(app, LoginExpiryStopReceiver::class.java).apply {
                action = LoginExpiryStopReceiver.ACTION_STOP
                putExtra(LoginExpiryStopReceiver.EXTRA_NOTIFICATION_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setContentTitle(expiry.title)
            .setContentText(expiry.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expiry.body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(0, "Stop these", stop)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(app).notify(id, notification)
    }

    private fun mayPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Login expired", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A saved login to one of your feeds was refused and needs redoing." }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "login_expired_channel"

        /** One slot per source, so a repeat of the same source replaces rather than stacks. */
        fun notificationId(source: String): Int = source.hashCode() and 0x3FFFFFFF
    }
}

/** The "Stop these" action: persists the flag, then takes the notice down. */
class LoginExpiryStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP) return
        LoginExpirySettings.setSilenced(context, true)
        NotificationManagerCompat.from(context)
            .cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    companion object {
        const val ACTION_STOP = "com.kevin.legion.action.LOGIN_EXPIRY_STOP"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}

/** Builds the poller over the real engine, store and notifier. */
object LoginExpiryPolling {
    fun poller(context: Context): LoginExpiryPoller {
        val app = context.applicationContext
        val notifier = LoginExpiryNotifier(app)
        return LoginExpiryPoller(
            backend = { djangoFreshnessBackendOrNull(app) },
            seen = PrefsExpirySeenStore(app),
            silenced = { LoginExpirySettings.silenced(app) },
            raise = notifier::raise,
        )
    }
}

/**
 * Owns the ONE alarm that runs the check about hourly, with the app closed. `ScheduledBackup` is
 * not the precedent for that despite being named as one: it only runs from `onResume`. The
 * app-closed precedent is `WellbeingDigestScheduler`: `setAndAllowWhileIdle` (no exact-alarm
 * permission), re-armed by the receiver on every fire, at app start, and by `BootReceiver` after
 * a reboot. WorkManager is not a dependency and its 15 minute floor buys nothing at an hourly
 * cadence.
 */
object LoginExpiryScheduler {
    private const val REQUEST_CODE = 90212 // distinct from WellbeingDigestScheduler's 90211
    const val INTERVAL_MS = 60L * 60 * 1000

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, LoginExpiryAlarmReceiver::class.java).apply {
                action = LoginExpiryAlarmReceiver.ACTION_FIRE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Arms (or replaces) the next check [INTERVAL_MS] from [now]. */
    fun arm(context: Context, now: Long = System.currentTimeMillis()) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, now + INTERVAL_MS, pendingIntent(context))
    }
}

class LoginExpiryAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // A hung request must not hold the receiver past its budget; a timeout is silence.
                withTimeoutOrNull(POLL_BUDGET_MS) {
                    runCatching { LoginExpiryPolling.poller(context).pollOnce() }
                }
            } finally {
                // Re-arm whatever happened: one failed poll is no reason to stop checking.
                LoginExpiryScheduler.arm(context)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.kevin.legion.action.LOGIN_EXPIRY_FIRE"
        private const val POLL_BUDGET_MS = 8_000L
    }
}
