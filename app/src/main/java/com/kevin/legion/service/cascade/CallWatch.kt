package com.kevin.legion.service.cascade

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import com.kevin.legion.service.TelephonyController
import com.kevin.legion.service.WakeWordEngine
import com.kevin.legion.service.turn.CallAudioMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reads the phone for [CallPause] while a conversation is open: the call state, the audio mode and
 * whose it is, telecom, our own capture being silenced (the Live path's and the wake word's
 * AudioRecordingCallback, ported) and audio focus (the listener the cascade's focus request lacked).
 * Read every [POLL_MS]; callbacks only update what the next read sees. [onPause] runs once.
 */
internal class CallWatch(
    private val ctx: Context,
    private val audio: AudioManager,
    private val onPause: (CallPause.Reason) -> Unit,
) {
    @Volatile private var ourSession = 0
    @Volatile private var silenced = false
    @Volatile private var focusLost = false
    private var job: Job? = null
    @Volatile private var startedAtMs = 0L
    private var recording: AudioManager.AudioRecordingCallback? = null
    private val debounce = CallPause.Debounce()

    /** The focus request's listener: a loss (not a duck) stands until focus comes back. */
    val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> focusLost = true
            AudioManager.AUDIOFOCUS_GAIN -> focusLost = false
        }
    }

    /** This conversation's own capture ([CascadeMic]): its silencing is ours, its config is not another's. */
    fun ourCapture(sessionId: Int) {
        ourSession = sessionId
        conversationSession = sessionId
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        startedAtMs = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    silenced = configs.firstOrNull { ourSession != 0 && it.clientAudioSessionId == ourSession }
                        ?.isClientSilenced ?: false
                }
            }
            recording = cb
            runCatching { audio.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper())) }
        }
        job = scope.launch {
            while (isActive) {
                debounce.feed(CallPause.reason(read()))?.let { onPause(it) }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        conversationSession = 0
        job?.cancel()
        recording?.let { cb -> runCatching { audio.unregisterAudioRecordingCallback(cb) } }
        recording = null
    }

    private fun read(): CallPause.Signals = signals(ctx, audio).copy(
        sinceStartMs = System.currentTimeMillis() - startedAtMs,
        micSilenced = silenced,
        focusLost = focusLost,
        // The focus rule as before: an unreadable list is no call capture here.
        otherCallCapture = focusLost && captures(audio)?.let { CallPause.callCapture(it, ours()) } == true,
    )

    companion object {
        const val POLL_MS = 250L

        /** This conversation's own capture while one runs (0: none). */
        @Volatile private var conversationSession = 0

        /** Another app's communication mode with no call in it, across the door and every watch. */
        private val staleComm = CallPause.StaleComm()

        /** The call signals any moment can read, with no conversation open. */
        fun signals(ctx: Context, audio: AudioManager?): CallPause.Signals {
            // Needs READ_PHONE_STATE; refused, telecom simply says nothing.
            val telecom = runCatching { ctx.getSystemService(TelecomManager::class.java) }.getOrNull()
        val playing = playingUsages(audio)
            val read = CallPause.Signals(
                cellularCall = TelephonyController.isInCall,
                ringing = TelephonyController.isRinging,
                telecomCall = runCatching { telecom?.isInCall == true }.getOrDefault(false),
                audioMode = audio?.mode ?: AudioManager.MODE_NORMAL,
                ownComm = CallAudioMode.ownCommActive,
                ringtone = playing?.contains(AudioAttributes.USAGE_NOTIFICATION_RINGTONE) == true,
                // Unreadable: assume a call (a stale mode is then never cleared, the old behaviour).
                otherCallAudio = playing?.contains(AudioAttributes.USAGE_VOICE_COMMUNICATION) ?: true,
                callCapture = CallPause.callCapture(captures(audio), ours()),
            )
            return read.copy(commStale = staleComm.feed(System.currentTimeMillis(), read))
        }

        /** The captures running now, any app's (anonymised ones keep source and session id); null: unreadable. */
        private fun captures(audio: AudioManager?): List<CallPause.Capture>? = runCatching {
            audio?.activeRecordingConfigurations
                ?.map { CallPause.Capture(it.clientAudioSessionId, it.clientAudioSource) }
        }.getOrNull()

        /** This conversation's mic and the wake word's, left out of [CallPause.callCapture]. */
        private fun ours(): Set<Int> = setOf(conversationSession, WakeWordEngine.captureSessionId) - 0

        /**
         * The usages playing now, any app's (null: unreadable). The voice-call usage is the other side of
         * a call (ours only while LEGION holds call audio, when the mode test does not count it); the
         * ringtone usage is a call ringing.
         */
        private fun playingUsages(audio: AudioManager?): Set<Int>? =
            if (audio == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                null
            } else {
                runCatching { audio.activePlaybackConfigurations.map { it.audioAttributes.usage }.toSet() }.getOrNull()
            }

        /**
         * The open's door (a tap, the wake word, the test door): a cellular or VoIP call is up. Replaces
         * the cellular-only `TelephonyController.isInCall` there.
         */
        fun isInCallNow(ctx: Context): Boolean =
            CallPause.blocksOpen(signals(ctx, ctx.getSystemService(AudioManager::class.java)))
    }
}
