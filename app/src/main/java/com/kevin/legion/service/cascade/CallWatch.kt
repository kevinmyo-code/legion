package com.kevin.legion.service.cascade

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import com.kevin.legion.service.TelephonyController
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
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
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
        job?.cancel()
        recording?.let { cb -> runCatching { audio.unregisterAudioRecordingCallback(cb) } }
        recording = null
    }

    private fun read(): CallPause.Signals = signals(ctx, audio).copy(
        micSilenced = silenced,
        focusLost = focusLost,
        otherCallCapture = focusLost && otherCallCapture(),
    )

    /** Another app capturing for a call; anonymised configs keep their source and session id. */
    private fun otherCallCapture(): Boolean = runCatching {
        audio.activeRecordingConfigurations.any {
            it.clientAudioSessionId != ourSession && it.clientAudioSource in CALL_SOURCES
        }
    }.getOrDefault(false)

    companion object {
        const val POLL_MS = 250L

        private val CALL_SOURCES = setOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_CALL,
        )

        /** The call signals any moment can read, with no conversation open. */
        fun signals(ctx: Context, audio: AudioManager?): CallPause.Signals = CallPause.Signals(
            cellularCall = TelephonyController.isInCall,
            ringing = TelephonyController.isRinging,
            // Needs READ_PHONE_STATE; refused, telecom simply says nothing.
            telecomCall = runCatching { ctx.getSystemService(TelecomManager::class.java)?.isInCall == true }
                .getOrDefault(false),
            audioMode = audio?.mode ?: AudioManager.MODE_NORMAL,
            ownComm = CallAudioMode.ownCommActive,
        )

        /**
         * The open's door (a tap, the wake word, the test door): a cellular or VoIP call is up. Replaces
         * the cellular-only `TelephonyController.isInCall` there.
         */
        fun isInCallNow(ctx: Context): Boolean =
            CallPause.blocksOpen(signals(ctx, ctx.getSystemService(AudioManager::class.java)))
    }
}
