package com.kevin.legion.navigation.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.kevin.legion.navigation.MapboxNavController
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.service.AssistantCueBridge
import com.kevin.legion.service.AssistantCueHold
import com.kevin.legion.service.MicArbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Says Mapbox's turn cues (mapbox-nav tickets 05 and 11): the SDK hands over the text at the moment
 * it times it, and this speaks it with the phone's own text-to-speech engine in one steady voice,
 * never the persona's. On-device so it works in dead zones. Mapbox's own voice player is never wired.
 *
 * The decisions (a cue wins, mute, back-to-back cues, no engine) are [NavCueArbiter]'s and are unit
 * tested; this class is the Android side of its [CueEnvironment]: the engine, audio focus, and the
 * two things a cue must take for its duration, the assistant's playback ([AssistantCueBridge]) and
 * the microphone ([MicArbiter.Claimant.NAV_CUE]).
 *
 * **Cues keep working with the screen off**: nothing here depends on the nav screen. The SDK's own
 * foreground service keeps the process alive, and this lives on the Application.
 *
 * **Nothing here has run on a phone**; the engine, the audio focus and the mic gate are verified by
 * compiling and, for behaviour, on the device (ticket 12).
 */
// The Android side of CueEnvironment: its six members plus the engine and audio-focus plumbing they need.
@Suppress("TooManyFunctions")
class NavCueSpeaker(
    private val context: Context,
    private val controller: MapboxNavController,
) : CueEnvironment {
    private val main = Handler(Looper.getMainLooper())
    private val arbiter = NavCueArbiter(this)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var tts: TextToSpeech? = null
    private var engineReady = false
    private var engineFailed = false

    /** A cue that arrived while the engine was still starting; said (or reported) when it is up. */
    private var waitingForEngine: String? = null

    /** Identifies the utterance in flight, so a stopped one's late "done" never ends the next cue. */
    private var utterance = 0
    private var held: AssistantCueHold? = null
    private var focus: AudioFocusRequest? = null

    override val muted: Boolean get() = controller.state.value.muted

    override val canSpeak: Boolean get() = !engineFailed

    /** Wires the controller's cues to this speaker and stops them when a trip ends or cues are muted. */
    fun attach(scope: CoroutineScope) {
        controller.cueSink = { text -> main.post { onCue(text) } }
        scope.launch {
            controller.state.map { it.phase to it.muted }.distinctUntilChanged().collect { (phase, muted) ->
                // Warm the engine as the trip starts, so the first cue is not waiting on it.
                if (phase == NavPhase.GUIDING) ensureEngine()
                // ARRIVED is left alone: the arrival cue is spoken as the trip finishes and must finish.
                if (muted || (phase != NavPhase.GUIDING && phase != NavPhase.ARRIVED)) arbiter.cancel()
            }
        }
    }

    private fun onCue(text: String) {
        if (!engineReady && !engineFailed) ensureEngine()
        arbiter.onCue(text)
    }

    // ------------------------------------------------------------------ CueEnvironment

    override fun hold() {
        // Refused while a live turn holds the mic (a cue never preempts a conversation); the live
        // session gates its own capture through the hold below instead.
        MicArbiter.request(MicArbiter.Claimant.NAV_CUE)
        held = AssistantCueBridge.current?.also { it.holdForCue() }
        requestFocus()
    }

    override fun release() {
        held?.releaseAfterCue()
        held = null
        MicArbiter.release(MicArbiter.Claimant.NAV_CUE)
        abandonFocus()
    }

    override fun speak(text: String): Boolean {
        val engine = tts
        if (!engineReady || engine == null) {
            // Still starting: keep the newest and say it the moment the engine is up.
            waitingForEngine = text
            val id = ++utterance
            main.postDelayed({ finished(id) }, WATCHDOG_MS)
            return true
        }
        return say(engine, text)
    }

    override fun stopSpeaking() {
        waitingForEngine = null
        utterance++
        tts?.stop()
    }

    override fun unspoken(text: String, why: String) {
        // The text is deliberately not logged: it carries a street name, which is a place.
        Log.w(TAG, "turn cue not spoken: $why")
        controller.noteCuesUnspoken()
    }

    override fun dropped(why: String) {
        // No cue text: it carries a street name, which is a place.
        Log.i(TAG, "turn cue dropped: $why")
    }

    // ------------------------------------------------------------------ engine

    private fun say(engine: TextToSpeech, text: String): Boolean {
        val id = ++utterance
        val started = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id.toString()) == TextToSpeech.SUCCESS
        if (started) main.postDelayed({ finished(id) }, WATCHDOG_MS)
        return started
    }

    private fun finished(id: Int) {
        if (id != utterance) return
        waitingForEngine = null
        arbiter.onSpeechDone()
    }

    private fun ensureEngine() {
        if (tts != null || engineFailed) return
        tts = TextToSpeech(context.applicationContext) { status -> main.post { onEngineInit(status) } }
    }

    private fun onEngineInit(status: Int) {
        val engine = tts
        if (engine == null || status != TextToSpeech.SUCCESS ||
            engine.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE
        ) {
            engineFailed = true
            engine?.shutdown()
            tts = null
            Log.w(TAG, "text-to-speech unavailable (status $status)")
            // A cue that was waiting on the engine ends here, said nowhere.
            waitingForEngine?.let { unspoken(it, "no speech engine") }
            waitingForEngine = null
            arbiter.onSpeechDone()
            return
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = post(utteranceId)

                @Deprecated("Deprecated in the framework; the two-argument form below is the live one")
                override fun onError(utteranceId: String?) = post(utteranceId)

                override fun onError(utteranceId: String?, errorCode: Int) = post(utteranceId)

                private fun post(utteranceId: String?) {
                    val id = utteranceId?.toIntOrNull() ?: return
                    main.post { finished(id) }
                }
            },
        )
        engineReady = true
        val pending = waitingForEngine
        waitingForEngine = null
        if (pending != null && !say(engine, pending)) {
            unspoken(pending, "the speech engine refused it")
            arbiter.onSpeechDone()
        }
    }

    // ------------------------------------------------------------------ audio focus

    // Other audio (Spotify) ducks under a cue the way it does under the assistant; whatever it is
    // doing resumes on its own when focus is abandoned.
    private fun requestFocus() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .build()
        focus = request
        audio.requestAudioFocus(request)
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private companion object {
        const val TAG = "NavCueSpeaker"

        /** If the engine never reports a cue finished, give the assistant and the mic back after this. */
        const val WATCHDOG_MS = 15_000L
    }
}
