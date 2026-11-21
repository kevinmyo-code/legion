package com.kevin.legion.service.cascade

import android.media.AudioManager

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
 * Ringing is not a call: "answer it" is said while the phone rings ([Signals.ringing]). Pure.
 */
internal object CallPause {
    enum class Reason(val token: String) { CALL("call"), VOIP("voip"), MIC_SILENCED("mic_silenced"), FOCUS("focus") }

    /** One reading of the phone. */
    data class Signals(
        /** A cellular call connected or dialling (OFFHOOK). */
        val cellularCall: Boolean,
        /** A cellular call ringing, unanswered. */
        val ringing: Boolean,
        /** TelecomManager says a call is up (any app's, ringing included). */
        val telecomCall: Boolean,
        val audioMode: Int,
        /** LEGION holds MODE_IN_COMMUNICATION itself ([com.kevin.legion.service.turn.CallAudioMode.ownCommActive]). */
        val ownComm: Boolean,
        /** The platform silences this conversation's own capture. */
        val micSilenced: Boolean = false,
        /** Audio focus lost (not a duck) and not regained since. */
        val focusLost: Boolean = false,
        /** Another capture for a call (VOICE_COMMUNICATION or VOICE_CALL) is running, not ours. */
        val otherCallCapture: Boolean = false,
    )

    fun reason(s: Signals): Reason? = callReason(s) ?: when {
        s.micSilenced -> Reason.MIC_SILENCED
        s.focusLost && s.otherCallCapture -> Reason.FOCUS
        else -> null
    }

    /** The open's door: no conversation starts while a call is up (the capture and focus signals need one open). */
    fun blocksOpen(s: Signals): Boolean = callReason(s) != null

    private fun callReason(s: Signals): Reason? = when {
        s.cellularCall || s.audioMode == AudioManager.MODE_IN_CALL -> Reason.CALL
        Backchannel.callOnLine(telephonyInCall = false, audioMode = s.audioMode, ownComm = s.ownComm) -> Reason.VOIP
        s.telecomCall && !s.ringing && s.audioMode != AudioManager.MODE_RINGTONE -> Reason.VOIP
        else -> null
    }

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
