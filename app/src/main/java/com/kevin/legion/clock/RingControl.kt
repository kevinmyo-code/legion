package com.kevin.legion.clock

import android.content.Context
import android.util.Log
import com.kevin.legion.service.SafetyAlarms

/** Why a ring stopped, as its audit line names it ([RingAudit]). */
enum class RingStopReason(val audit: String) {
    /** The in-app Stop bar (Alfred home, the Clock screen). */
    BUTTON("button"),
    /** The ring notification's Stop. */
    NOTIFICATION("notification"),
    /** "Stop" or "stop the alarm" said while it rang. */
    VOICE("voice"),
    /** The ring screen's Dismiss, Stop or Brief me. */
    SCREEN("screen"),
    /** Rang out: [ClockDefaults.AUTO_SILENCE_MIN] with nobody answering. */
    TIMEOUT("timeout"),
    /** The ring notification swiped away. */
    SWIPE("swipe"),
    /** An alarm's Snooze, on the screen or the notification. */
    SNOOZE("snooze"),
    /** The clock itself: an alarm turned off or deleted, a timer cancelled or given a minute. */
    CLOCK("clock"),
}

/**
 * The one owner of stopping a ring (phantom alarm fix, 2026-11-21). Every Stop goes through
 * [stopAll] or [stop]: the in-app Stop bar, the notification's Stop and its swipe, the voice stop,
 * the ring screen and the auto-silence. Each ring is ended the way its kind ends (an alarm is
 * dismissed and re-armed, a timer is finished, an emergency's facts are kept), never by clearing
 * the service's list behind the clock's back: a one-off alarm left enabled would ring again on the
 * next re-arm, inside the 10-minute grace.
 */
object RingControl {
    /** Stops everything that rings; how many there were. */
    fun stopAll(context: Context, reason: RingStopReason): Int {
        val now = RingService.ringing.value
        now.forEach { stop(context, it, reason) }
        return now.size
    }

    /**
     * Stops [r] the way its kind stops, then makes sure the sound for it is gone even if that
     * failed. An emergency stopped by a person looking at it (screen, notification, the bar) gets
     * its line said after; one stopped blind (voice, swipe, rung out) has its facts posted instead,
     * so a voice stop never starts a spoken line into a conversation that is ending.
     */
    fun stop(context: Context, r: RingRequest, reason: RingStopReason) {
        RingService.noteReason(r.kind, r.id, reason)
        runCatching {
            val clock = ClockGraph.controller(context)
            when (r.kind) {
                RingKind.ALARM -> clock.alarms.dismiss(r.id, silenced = reason == RingStopReason.TIMEOUT)
                RingKind.TIMER -> clock.timers.remove(r.id)
                RingKind.ALERT -> if (reason in ATTENDED) {
                    SafetyAlarms.dismissed(context, r.id)
                } else {
                    SafetyAlarms.rangOut(context, r.id)
                }
            }
        }.onFailure { Log.e(RingAudit.TAG, "stopping ${r.kind.audit} failed; silencing it anyway", it) }
        RingService.stop(r.kind, r.id)
    }

    /** Snooze on the ring screen or the notification: the alarm rings again in [ClockDefaults.SNOOZE_MIN]. */
    fun snooze(context: Context, id: Int) {
        RingService.noteReason(RingKind.ALARM, id, RingStopReason.SNOOZE)
        ClockGraph.controller(context).alarms.snooze(id)
    }

    /**
     * True when something rings and [words] (his whole turn) are a stop: the cascade then stops
     * every ring before it ends the conversation. Read on the transcript, so no model is asked.
     */
    fun voiceStops(words: String, companionName: String = ""): Boolean =
        RingService.ringing.value.isNotEmpty() && isStopPhrase(words, companionName)

    /** [words] are one of [STOP_PHRASES] once the name, "hey"/"ok" and "please" are set aside. */
    internal fun isStopPhrase(words: String, companionName: String = ""): Boolean {
        val name = tokens(companionName).toSet()
        val core = tokens(words).filter { it !in name && it !in LEAD_INS }
        return core.isNotEmpty() && core.joinToString(" ") in STOP_PHRASES
    }

    private fun tokens(text: String): List<String> =
        text.lowercase().replace("'", "").split(NOT_A_WORD).filter { it.isNotBlank() }

    private val NOT_A_WORD = Regex("[^a-z0-9]+")

    /** The emergency is stopped by someone looking at it: the line may be said after. */
    private val ATTENDED = setOf(RingStopReason.SCREEN, RingStopReason.NOTIFICATION, RingStopReason.BUTTON)

    private val LEAD_INS = setOf("hey", "ok", "okay", "please")

    /** Whole turns only: "stop the music" is a request, never a ring stop. */
    private val STOP_PHRASES = setOf(
        "stop", "stop stop", "stop it", "stop that", "stop ringing", "stop the ringing", "stop the noise",
        "stop the alarm", "stop alarm", "stop the timer", "stop timer", "stop the alert", "stop the emergency alert",
        "turn it off", "turn that off", "turn off the alarm", "turn the alarm off", "alarm off",
        "dismiss", "dismiss it", "dismiss the alarm", "dismiss the alert",
        "silence", "silence it", "silence the alarm", "quiet", "be quiet", "shut up", "enough",
    )
}

/**
 * The ring's audit (phantom alarm fix): one line when a ring starts and one when it stops, with the
 * kind ([RingKind.audit]) and the reason ([RingStopReason.audit]). Never the label: an emergency's
 * label is the alert's own words. Read back through the phone log upload.
 */
object RingAudit {
    const val TAG = "AlfredRing"

    fun started(kind: RingKind) {
        Log.i(TAG, startLine(kind))
    }

    fun stopped(kind: RingKind, reason: RingStopReason) {
        Log.i(TAG, stopLine(kind, reason))
    }

    /** An emergency that did not ring because nothing could show it ([RingNotification.canShow]). */
    fun skipped(kind: RingKind) {
        Log.w(TAG, "ring_skip kind=${kind.audit} reason=$BLOCKED")
    }

    /** A clock ring with nothing on screen: he set it, so it rings, and this says why it is unseen. */
    fun blocked(kind: RingKind) {
        Log.w(TAG, "ring_blocked kind=${kind.audit} reason=$BLOCKED")
    }

    private const val BLOCKED = "notifications_blocked"

    internal fun startLine(kind: RingKind): String = "ring_start kind=${kind.audit}"

    internal fun stopLine(kind: RingKind, reason: RingStopReason): String =
        "ring_stop kind=${kind.audit} reason=${reason.audit}"
}
