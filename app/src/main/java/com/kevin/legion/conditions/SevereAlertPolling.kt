package com.kevin.legion.conditions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kevin.legion.location.LocationController
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.service.ProactiveSettings
import com.kevin.legion.service.ProactiveUrgency
import com.kevin.legion.ui.LegionRoute
import com.kevin.legion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The ONE [SevereAlertWatcher] for the process and the two existing loops that run it: while a
 * LEGION screen is in front ([bindForeground], from `MidnightApplication`) and while
 * `AriaForegroundService` runs. The `ClaudeResultPolling` shape exactly: no alarm, no WorkManager.
 * With the assistant off and the app closed nothing polls.
 *
 * **It asks the engine nothing while the Safety switch is off** ([ProactiveSettings.mayRaise]): a
 * raise that could never be spoken is not worth a request. Delivery goes through the proactive
 * registry ([ProactiveBus.speakIfAllowed], category [ProactiveCategory.SAFETY]: allowed in quiet
 * hours, inside the master switch), which speaks it when it may and posts it when it may not; a
 * Severe or Extreme alert RINGS instead in quiet hours or on a silenced phone ([raise]). Tapping the
 * notification opens the Conditions screen.
 */
object SevereAlertPolling {
    @Volatile private var watcher: SevereAlertWatcher? = null

    fun watcher(context: Context): SevereAlertWatcher {
        watcher?.let { return it }
        return synchronized(this) {
            watcher ?: build(context.applicationContext).also { watcher = it }
        }
    }

    private fun build(app: Context): SevereAlertWatcher {
        val controller = ConditionsController.forContext(app)
        return SevereAlertWatcher(
            check = { point ->
                ProactiveSettings.load(app)
                if (!ProactiveSettings.mayRaise(ProactiveCategory.SAFETY)) {
                    ConditionsOutcome.Failed("Safety raises are switched off; nothing was asked.")
                } else {
                    controller.check(point, kinds = listOf("alerts"), severeOnly = true)
                }
            },
            here = { LocationController.state.value?.let { ConditionsPoint.Here(it.latitude, it.longitude) } },
            seenStore = PrefsSeenAlerts(app),
            announce = { alert, where -> announce(app, alert, where) },
        )
    }

    /**
     * The raise for one alert. **A Severe or Extreme alert is an emergency [ProactiveUrgency.Alarm]**
     * (ADR 0082, Kevin by name: "if any emergency in the area is noted, alarm me"): Safety only; it
     * RINGS in quiet hours or on a silenced phone and is spoken otherwise (posted in a meeting);
     * one ring per alert id, again only when its severity rises (`SafetyAlarms`). Pure but for the
     * tap target, so it is tested without Android.
     */
    internal fun raise(alert: SevereAlert, where: String, contentIntent: PendingIntent?): ProactiveRaise {
        val facts = SevereAlertWatcher.facts(alert, where)
        return ProactiveRaise(
            // One rule per alert, so brushing off one never suppresses the next warning.
            ruleId = "severe_alert:${alert.id}",
            category = ProactiveCategory.SAFETY,
            reason = facts,
            facts = facts,
            prompt = SevereAlertWatcher.prompt(facts),
            contentIntent = contentIntent,
            urgency = SevereAlertWatcher.alarmLevel(alert.severity)
                ?.let { ProactiveUrgency.Alarm(alertId = alert.id, severity = it) }
                ?: ProactiveUrgency.Ordinary,
        )
    }

    private suspend fun announce(context: Context, alert: SevereAlert, where: String): AlertDelivery {
        val raise = raise(alert, where, openConditions(context))
        // Rang and AlreadyAlarmed are delivered; AlarmNotAllowed cannot happen (Safety only).
        return when (ProactiveBus.speakIfAllowed(context, raise)) {
            ProactiveBus.RaiseOutcome.NotNow -> AlertDelivery.RETRY
            else -> AlertDelivery.DELIVERED
        }
    }

    private fun openConditions(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.CONDITIONS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Polls while the app is in front; call once from `Application.onCreate` (main thread). */
    fun bindForeground(context: Context, scope: CoroutineScope) {
        val watcher = watcher(context)
        var loop: Job? = null
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    if (loop?.isActive != true) loop = scope.launch { watcher.run() }
                }

                override fun onStop(owner: LifecycleOwner) {
                    loop?.cancel()
                    loop = null
                }
            },
        )
    }

    private const val REQUEST_CODE = 0x5EA7
}

/** [SeenAlerts] in SharedPreferences: `id -> raised at`, pruned after two days. */
class PrefsSeenAlerts(context: Context) : SeenAlerts {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun seen(): Map<String, Long> =
        prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    override fun markSeen(id: String, atMs: Long) {
        prefs.edit().putLong(id, atMs).apply()
    }

    override fun forgetOlderThan(cutoffMs: Long) {
        val stale = seen().filterValues { it < cutoffMs }.keys
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    private companion object {
        const val PREFS = "severe_alerts_seen"
    }
}
