package com.kevin.legion.books

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the text-to-speech engine under the reader reports. */
sealed interface EngineStatus {
    data object Ready : EngineStatus

    /** Reached the end of the book. */
    data object Ended : EngineStatus

    /** The system asked for it to stop (the notification was dismissed); a plain stop, nothing to explain. */
    data object Stopped : EngineStatus

    /** [sentence] says in words what went wrong and the fix (e.g. missing voice data). */
    data class Failed(val sentence: String) : EngineStatus
}

/**
 * The seam over Readium's TTS navigator. Production wraps `AndroidTtsNavigator`; tests use a fake,
 * so the controller's rules (focus, speed, stop) are exercised without a speech engine.
 */
interface ReadAloudEngine {
    val playing: StateFlow<Boolean>
    val status: StateFlow<EngineStatus>

    /** The locator (as JSON) of the sentence being spoken, for the highlight; null before the first. */
    val utterance: StateFlow<String?>

    fun play()

    fun pause()

    fun setSpeed(speed: Float)

    /** Releases the engine and its media session. Idempotent. */
    fun close()
}

/** Either an engine ready to speak, or the sentence for why there is none. */
sealed interface EngineResult {
    data class Ready(val engine: ReadAloudEngine) : EngineResult

    data class Unavailable(val sentence: String) : EngineResult
}

/** Audio focus, as the controller needs it. Android's `AudioManager` in production. */
interface AudioFocusGate {
    /** Asks for focus; [onLost] is called when it is taken. Returns whether it was granted. */
    fun request(onLost: () -> Unit): Boolean

    fun abandon()
}

/** What the reader draws for read-aloud. */
data class ReadAloudUi(
    /** A session exists (playing or paused). */
    val active: Boolean = false,
    val playing: Boolean = false,
    val speed: Float = 1.0f,
    val utteranceJson: String? = null,
    /** A line the reader shows in words: why it stopped, or what failed. Null when all is well. */
    val message: String? = null,
) {
    val speedLabel: String get() = "${if (speed % 1f == 0f) speed.toInt().toString() + ".0" else speed.toString()}x"
}

/**
 * Read-aloud for the reader (ADR 0061): one session at a time, offline device TTS, and it STOPS when
 * audio focus is taken (a call, another player) rather than talking over it. Pausing is not
 * stopping: [pause] keeps the session and the lock-screen controls, [stop] ends them.
 *
 * Nothing here claims the voice is speaking until the engine says so: [state].playing mirrors the
 * engine's own flag, so a Listen button never shows "reading" over a silent phone.
 */
class ReadAloudController(
    private val scope: CoroutineScope,
    private val focus: AudioFocusGate,
    private val create: suspend (startLocatorJson: String?) -> EngineResult,
) {
    private val _state = MutableStateFlow(ReadAloudUi())
    val state: StateFlow<ReadAloudUi> = _state.asStateFlow()

    private var engine: ReadAloudEngine? = null
    private var watch: Job? = null
    private var starting = false

    /** Begins reading from [startLocatorJson] (the first visible sentence), or resumes a paused session. */
    fun start(startLocatorJson: String?) {
        engine?.let { resume(it); return }
        if (starting) return
        starting = true
        scope.launch {
            try {
                when (val result = create(startLocatorJson)) {
                    is EngineResult.Unavailable -> _state.update { it.copy(message = result.sentence) }
                    is EngineResult.Ready -> attach(result.engine)
                }
            } finally {
                starting = false
            }
        }
    }

    private fun attach(created: ReadAloudEngine) {
        if (!focus.request(onLost = ::onFocusLost)) {
            created.close()
            _state.update { it.copy(message = FOCUS_REFUSED) }
            return
        }
        engine = created
        created.setSpeed(_state.value.speed)
        _state.update { it.copy(active = true, message = null) }
        watch = scope.launch {
            launch { created.playing.collect { p -> _state.update { it.copy(playing = p) } } }
            launch { created.utterance.collect { u -> _state.update { it.copy(utteranceJson = u) } } }
            launch {
                created.status.collect { status ->
                    when (status) {
                        EngineStatus.Ready -> Unit
                        EngineStatus.Ended -> stop("Reached the end of the book.")
                        EngineStatus.Stopped -> stop()
                        is EngineStatus.Failed -> stop(status.sentence)
                    }
                }
            }
        }
        created.play()
    }

    private fun resume(current: ReadAloudEngine) {
        if (!focus.request(onLost = ::onFocusLost)) {
            _state.update { it.copy(message = FOCUS_REFUSED) }
            return
        }
        _state.update { it.copy(message = null) }
        current.play()
    }

    fun pause() {
        engine?.pause()
    }

    /** Play/pause for the one button the reader shows. */
    fun toggle(startLocatorJson: String?) {
        if (_state.value.playing) pause() else start(startLocatorJson)
    }

    /** Steps through [SPEEDS]; the engine picks it up now and on any later session. */
    fun cycleSpeed() {
        val next = SPEEDS[(SPEEDS.indexOf(_state.value.speed) + 1).mod(SPEEDS.size)]
        _state.update { it.copy(speed = next) }
        engine?.setSpeed(next)
    }

    /** Ends the session and its media controls. [reason] is shown in words, or null for a plain stop. */
    fun stop(reason: String? = null) {
        watch?.cancel()
        watch = null
        engine?.close()
        engine = null
        focus.abandon()
        _state.update { it.copy(active = false, playing = false, utteranceJson = null, message = reason) }
    }

    private fun onFocusLost() {
        if (engine == null) return
        engine?.pause()
        _state.update { it.copy(message = FOCUS_LOST) }
    }

    companion object {
        val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        const val FOCUS_LOST = "Paused: another app is using the audio. Tap Listen to carry on."
        const val FOCUS_REFUSED = "Another app is using the audio, so reading aloud did not start."
    }
}
