package com.kevin.legion.service.cascade

import android.media.AudioManager
import android.media.MediaRecorder

/**
 * A conversation never listens through someone's phone call (2026-11-21; report
 * voice-front-back-split-1120 section 8.2). The cascade checked for a call only at its open, and only a
 * cellular one; tonight it kept listening through a call and the laptop's speech-to-text got two
 * voices. The retired Live path never closed mid-conversation either: it refused at the open
 * ([com.kevin.legion.service.TelephonyController.isInCall]), logged a silenced capture and noted a
 * focus loss. Closing is new here.
 *
 * [reason] reads one moment ([Signals]); a conversation closes quietly (no words, no closing chime)
 * once the same reason is read twice in a row ([Debounce]). The open's door ([blocksOpen]) refuses on
 * the same call signals, so the wake word starts nothing during a call.
 *
 * Whose mode is it: MODE_IN_COMMUNICATION is LEGION's own while it holds call audio for its earpiece,
 * speaker or Bluetooth route ([Signals.ownComm], [Backchannel.callOnLine]'s same test). A VoIP call
 * that starts while LEGION holds that mode cannot be told from it by the mode; it is seen by one of:
 * telecom reporting a call (a VoIP app with a self-managed connection), the platform silencing our
 * capture, or a focus loss while another app captures for a call (VOICE_COMMUNICATION or VOICE_CALL).
 * A bare focus loss (music, a navigation prompt, an alarm) closes nothing.
 *
 * Ringing is not a call: "answer it" is said while the phone rings ([Signals.ringing]), and a VoIP
 * call ringing ([Signals.ringtone]: a ringtone plays; telecom reports a self-managed VoIP call while
 * it rings, and has no public "ringing" for it) closes nothing until it is answered.
 *
 * Review of 264baf63 (2026-11-21), each a way to hang up on him for nothing:
 * - The capture signals (silenced, focus + another call capture) count only [CAPTURE_GRACE_MS] after
 *   the watch started: the wake word's own VOICE_COMMUNICATION recorder hands the mic over a moment
 *   after the open (its session id is left out of [callCapture] too, once it is known).
 * - Another app's communication mode with no sign of a call (no voice-call playback, no capture for a
 *   call, telecom and the cellular state quiet) for [STALE_COMM_MS] is stale ([StaleComm]), as a
 *   crashed VoIP app leaves it: it no longer counts, so it cannot refuse every open forever. A real
 *   VoIP call plays its other side or captures for it the whole time, held or muted, so it is never
 *   stale.
 *
 * Pure.
 */
internal object CallPause {
    enum class Reason(val token: String) { CALL("call"), VOIP("voip"), MIC_SILENCED("mic_silenced"), FOCUS("focus") }

    /** The capture signals are ignored this long after the watch started (the wake word's hand-over). */
    const val CAPTURE_GRACE_MS = 1_500L

    /** Another app's communication mode with no call audio and no telecom call this long: stale. */
    const val STALE_COMM_MS = 10_000L

    /** One reading of the phone. */
    data class Signals(
        /** A cellular call connected or dialling (OFFHOOK). */
        val cellularCall: Boolean,
        /** A cellular call ringing, unanswered. */
        val ringing: Boolean,
        /** TelecomManager says a call is up (any app's, ringing included). */
        val telecomCall: Boolean,
        val audioMode: Int,
        /** LEGION holds MODE_IN_COMMUNICATION itself (CallAudioMode.ownCommActive). */
        val ownComm: Boolean,
        /** Something plays on the ringtone usage (a VoIP app's ring included). */
        val ringtone: Boolean = false,
        /** Another app plays on the voice-call usage (the other side of a call). */
        val otherCallAudio: Boolean = true,
        /** Another app captures for a call, focus or not ([callCapture]; unreadable reads as true). */
        val callCapture: Boolean = true,
        /** Another app's communication mode has looked stale for [STALE_COMM_MS] ([StaleComm]). */
        val commStale: Boolean = false,
        /** Since the watch started; the capture signals wait [CAPTURE_GRACE_MS]. */
        val sinceStartMs: Long = Long.MAX_VALUE,
        /** The platform silences this conversation's own capture. */
        val micSilenced: Boolean = false,
        /** Audio focus lost (not a duck) and not regained since. */
        val focusLost: Boolean = false,
        /** Another capture for a call (VOICE_COMMUNICATION or VOICE_CALL) is running, not ours. */
        val otherCallCapture: Boolean = false,
    )

    fun reason(s: Signals): Reason? = callReason(s) ?: when {
        s.sinceStartMs < CAPTURE_GRACE_MS -> null
        s.micSilenced -> Reason.MIC_SILENCED
        s.focusLost && s.otherCallCapture -> Reason.FOCUS
        else -> null
    }

    /** The open's door: no conversation starts while a call is up (the capture and focus signals need one open). */
    fun blocksOpen(s: Signals): Boolean = callReason(s) != null

    private fun callReason(s: Signals): Reason? = when {
        s.cellularCall || s.audioMode == AudioManager.MODE_IN_CALL -> Reason.CALL
        othersComm(s) && !s.commStale -> Reason.VOIP
        s.telecomCall && !ringing(s) -> Reason.VOIP
        else -> null
    }

    private fun ringing(s: Signals): Boolean =
        s.ringing || s.ringtone || s.audioMode == AudioManager.MODE_RINGTONE

    private fun othersComm(s: Signals): Boolean =
        Backchannel.callOnLine(telephonyInCall = false, audioMode = s.audioMode, ownComm = s.ownComm)

    /**
     * How long another app's communication mode has looked like no call at all (no voice-call audio,
     * no telecom call, no cellular call). Any sign of a call, or the mode going, starts it again.
     * One per process: the door and the watch feed the same one.
     */
    class StaleComm {
        private var since: Long? = null

        /** True once [s] has looked stale for [STALE_COMM_MS], counting from the first such reading. */
        @Synchronized
        fun feed(nowMs: Long, s: Signals): Boolean {
            // A call on hold or muted may stop its playback; its capture runs on (re-check of cdfc8934).
            val signOfCall = s.otherCallAudio || s.callCapture || s.telecomCall || s.cellularCall
            val looks = othersComm(s) && !signOfCall
            since = if (looks) since ?: nowMs else null
            return since?.let { nowMs - it >= STALE_COMM_MS } == true
        }
    }

    /** One running capture, as the platform lists it (another app's is anonymised but keeps these). */
    data class Capture(val sessionId: Int, val source: Int)

    /**
     * Another app captures for a call (VOICE_COMMUNICATION or VOICE_CALL), not one of [ours] (this
     * conversation's mic, the wake word's). An unreadable list ([captures] null) reads as one.
     */
    fun callCapture(captures: List<Capture>?, ours: Set<Int> = emptySet()): Boolean =
        captures?.any { it.sessionId !in ours && it.source in CALL_SOURCES } ?: true

    private val CALL_SOURCES =
        setOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_CALL)

    /** `conversation_paused reason=<token>`: a fixed token, nothing personal. */
    fun line(reason: Reason): String = "conversation_paused reason=${reason.token}"

    /** The close reason: not one [CloseCue] plays the chime for. */
    fun closeReason(reason: Reason): String = "paused_${reason.token}"

    /**
     * The same reason read twice in a row fires, once. One reading is not enough: LEGION's own call
     * audio sets the mode a moment before it marks it as its own, and clears the mark a moment before
     * it restores the mode ([com.kevin.legion.service.turn.CallAudioMode]).
     */
    class Debounce {
        private var last: Reason? = null
        private var fired = false

        /** The reason to act on now, or null. */
        @Synchronized
        fun feed(reason: Reason?): Reason? {
            val fire = !fired && reason != null && reason == last
            last = reason
            if (fire) fired = true
            return if (fire) reason else null
        }
    }
}
