package com.kevin.legion.service

import android.content.Context
import android.util.Log
import com.kevin.legion.clock.RingKind
import com.kevin.legion.clock.RingRequest
import com.kevin.legion.clock.RingService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The delivery half of an emergency alarm (ADR 0082, Kevin 2026-11-10: *"also quiet hours, if any
 * emergency in the area is noted, alarm me"*). The FEED that decides there is an emergency is not
 * here; it raises a Safety [ProactiveRaise] with [ProactiveUrgency.Alarm] and the bus decides.
 *
 * - **One alarm per alert id**, and a second only when the severity rises ([isNew], [remember]).
 *   Kept in SharedPreferences, not Room: an id and a number, no schema change, pruned after
 *   [KEEP_DAYS].
 * - **The ring is the clock's** (ADR 0079): [RingService] plays the alarm stream, which sounds
 *   through silent and vibrate, and [com.kevin.legion.ui.clock.RingActivity] shows the facts over
 *   the lock screen with one Dismiss. [ring] returns false when Android refuses to start it from
 *   the background, and the bus then posts the raise instead of dropping it.
 * - **After Dismiss, Alfred says the line** ([dismissed] then [ProactiveBus.speakAfterAlarm]).
 *   Rung out unanswered, the facts are posted ([rangOut]).
 *
 * The held raise lives in memory: a process death between the ring and Dismiss loses the spoken
 * line, never the facts, which were on the screen and stay in the ring notification.
 */
object SafetyAlarms {
    private const val TAG = "SafetyAlarms"
    private const val PREFS = "safety_alarms"
    private const val KEEP_DAYS = 14L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** How long [dismissed] waits for the assistant service before posting instead. */
    private const val LISTENER_WAIT_MS = 3_000L

    private val held = ConcurrentHashMap<Int, ProactiveRaise>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** True when [alarm] has not been delivered before at this severity or higher. */
    fun isNew(context: Context, alarm: ProactiveUrgency.Alarm): Boolean =
        isNewGiven(stored(context, alarm.alertId), alarm.severity)

    /** Pure, for the test: [storedSeverity] null means never seen. */
    internal fun isNewGiven(storedSeverity: Int?, severity: Int): Boolean =
        storedSeverity == null || severity > storedSeverity

    /** Records that [alarm] was delivered, so the same alert does not ring again at the same level. */
    fun remember(context: Context, alarm: ProactiveUrgency.Alarm, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        prefs.all.forEach { (key, value) ->
            val at = (value as? String)?.substringAfter('|')?.toLongOrNull() ?: 0L
            if (now - at > KEEP_DAYS * DAY_MS) edit.remove(key)
        }
        edit.putString(alarm.alertId, "${alarm.severity}|$now").apply()
    }

    private fun stored(context: Context, alertId: String): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(alertId, null)
            ?.substringBefore('|')?.toIntOrNull()

    /** The ring id for an alert: stable per id, never negative. */
    internal fun ringId(alertId: String): Int = alertId.hashCode() and Int.MAX_VALUE

    /** Starts the ring with the facts on screen. False when it could not start; nothing is held then. */
    fun ring(context: Context, raise: ProactiveRaise): Boolean {
        val alarm = raise.urgency as? ProactiveUrgency.Alarm ?: return false
        val id = ringId(alarm.alertId)
        held[id] = raise
        return runCatching {
            val label = raise.notificationText ?: raise.facts
            RingService.start(context.applicationContext, RingRequest(RingKind.ALERT, id, label))
            true
        }.getOrElse {
            // ForegroundServiceStartNotAllowedException on Android 12+ when nothing exempts the start.
            Log.w(TAG, "the emergency ring could not start; posting instead", it)
            held.remove(id)
            false
        }
    }

    /** Dismiss on the ring screen: stop the ring, then say the line. */
    fun dismissed(context: Context, id: Int) {
        RingService.stop(RingKind.ALERT, id)
        val raise = held.remove(id) ?: return
        val app = context.applicationContext
        scope.launch {
            ProactiveDelivery.awaitListener(LISTENER_WAIT_MS)
            ProactiveBus.speakAfterAlarm(app, raise)
        }
    }

    /** Rang out with nobody dismissing it: the facts go to the shade so they are not lost. */
    fun rangOut(context: Context, id: Int) {
        val raise = held.remove(id) ?: return
        ProactiveDelivery.notify(context, raise, raise.notificationText ?: raise.facts)
    }
}
