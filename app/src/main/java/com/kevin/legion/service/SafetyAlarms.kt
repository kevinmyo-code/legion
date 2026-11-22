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
 * - **One alarm per alert series**, and a second only when the severity rises ([admit],
 *   [remember], [EmergencyRingRules]): an NWS update of an alert that rang does not ring again.
 *   Kept in SharedPreferences, not Room: an id and a few fields, no schema change, pruned after
 *   [KEEP_DAYS].
 * - **At most [EmergencyRingRules.MAX_RINGS_PER_NIGHT] rings a night** ([ring]); past it the bus
 *   posts the alert instead.
 * - **The ring is the clock's** (ADR 0079): [RingService] plays the alarm stream, which sounds
 *   through silent and vibrate, and [com.kevin.legion.ui.clock.RingActivity] shows the facts over
 *   the lock screen. [ring] returns false when it may not or cannot start, and the bus then posts
 *   the raise instead of dropping it.
 * - **After a Stop someone saw, Alfred says the line** ([dismissed] then
 *   [ProactiveBus.speakAfterAlarm]). Rung out, or stopped blind (voice, swipe), the facts are posted
 *   ([rangOut]).
 *
 * The held raise lives in memory: a process death between the ring and Dismiss loses the spoken
 * line, never the facts, which were on the screen and stay in the ring notification.
 */
object SafetyAlarms {
    private const val TAG = "SafetyAlarms"
    private const val PREFS = "safety_alarms"
    private const val RINGS_PREFS = "safety_alarm_rings"
    private const val RINGS_KEY = "at"
    private const val KEEP_DAYS = 14L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** How long [dismissed] waits for the assistant service before posting instead. */
    private const val LISTENER_WAIT_MS = 3_000L

    private val held = ConcurrentHashMap<Int, ProactiveRaise>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * True when [alarm] may be delivered: its series has not been delivered at this severity or
     * higher. A repeat is still remembered, linked into its series, so a later update that names
     * only the repeat is known too.
     */
    fun admit(context: Context, alarm: ProactiveUrgency.Alarm, now: Long = System.currentTimeMillis()): Boolean {
        val isNew = EmergencyRingRules.isNew(alarm, records(context))
        if (!isNew) remember(context, alarm, now)
        return isNew
    }

    /** Records that [alarm] was delivered (or repeated), in its series, never lowering its severity. */
    fun remember(context: Context, alarm: ProactiveUrgency.Alarm, now: Long = System.currentTimeMillis()) {
        val records = records(context)
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        records.forEach { (key, r) -> if (now - r.atMs > KEEP_DAYS * DAY_MS) edit.remove(key) }
        val severity = maxOf(alarm.severity, records[alarm.alertId]?.severity ?: 0)
        val series = EmergencyRingRules.seriesOf(alarm, records)
        val record = EmergencyRingRules.Record(severity, now, series)
        edit.putString(alarm.alertId, EmergencyRingRules.format(record)).apply()
    }

    private fun records(context: Context): Map<String, EmergencyRingRules.Record> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            (value as? String)?.let { EmergencyRingRules.parse(key, it) }?.let { key to it }
        }.toMap()

    /** The ring id for an alert: stable per id, never negative. */
    internal fun ringId(alertId: String): Int = alertId.hashCode() and Int.MAX_VALUE

    /**
     * Starts the ring with the facts on screen. False when the night's cap is reached or it could
     * not start; nothing is held then and the bus posts it.
     */
    fun ring(context: Context, raise: ProactiveRaise, now: Long = System.currentTimeMillis()): Boolean {
        val alarm = raise.urgency as? ProactiveUrgency.Alarm ?: return false
        val rings = context.getSharedPreferences(RINGS_PREFS, Context.MODE_PRIVATE)
        val recent = rings.getString(RINGS_KEY, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
            .filter { now - it < EmergencyRingRules.CAP_WINDOW_MS }
        val capped = !EmergencyRingRules.underCap(recent, now)
        if (capped) Log.i(TAG, "emergency ring cap reached (${EmergencyRingRules.MAX_RINGS_PER_NIGHT}); posted instead")
        val id = ringId(alarm.alertId)
        if (!capped) held[id] = raise
        return !capped && runCatching {
            val label = raise.notificationText ?: raise.facts
            RingService.start(context.applicationContext, RingRequest(RingKind.ALERT, id, label))
            rings.edit().putString(RINGS_KEY, (recent + now).joinToString(",")).apply()
            true
        }.getOrElse {
            // ForegroundServiceStartNotAllowedException on Android 12+ when nothing exempts the start.
            Log.w(TAG, "the emergency ring could not start; posting instead", it)
            held.remove(id)
            false
        }
    }

    /** Stopped by someone who saw it (ring screen, notification, the in-app bar): stop, then say the line. */
    fun dismissed(context: Context, id: Int) {
        RingService.stop(RingKind.ALERT, id)
        val raise = held.remove(id) ?: return
        val app = context.applicationContext
        scope.launch {
            ProactiveDelivery.awaitListener(LISTENER_WAIT_MS)
            ProactiveBus.speakAfterAlarm(app, raise)
        }
    }

    /** Rang out, or stopped blind (voice, swipe): the facts go to the shade so they are not lost. */
    fun rangOut(context: Context, id: Int) {
        val raise = held.remove(id) ?: return
        ProactiveDelivery.notify(context, raise, raise.notificationText ?: raise.facts)
    }
}
