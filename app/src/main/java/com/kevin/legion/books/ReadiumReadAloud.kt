package com.kevin.legion.books

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONObject
import org.readium.navigator.media.tts.AndroidTtsNavigator
import org.readium.navigator.media.tts.AndroidTtsNavigatorFactory
import org.readium.navigator.media.tts.TtsNavigator
import org.readium.navigator.media.tts.android.AndroidTtsEngine
import org.readium.navigator.media.tts.android.AndroidTtsPreferences
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse

/**
 * [ReadAloudEngine] over Readium's `AndroidTtsNavigator` (device TTS, offline). Playback runs through
 * a media3 session ([ReadAloudSessions]) so it keeps going with the screen off and the lock screen
 * has play and pause. EPUB only: the factory is built from an EPUB publication.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReadiumReadAloudEngine private constructor(
    private val navigator: AndroidTtsNavigator,
    private val stopRequested: MutableStateFlow<Boolean>,
    scope: CoroutineScope,
) : ReadAloudEngine {
    override val playing: StateFlow<Boolean> =
        navigator.playback.map { it.playWhenReady }.stateIn(scope, SharingStarted.Eagerly, false)

    override val status: StateFlow<EngineStatus> =
        combine(navigator.playback, stopRequested) { playback, stop ->
            if (stop) {
                EngineStatus.Stopped
            } else {
                when (val state = playback.state) {
                    is TtsNavigator.State.Ended -> EngineStatus.Ended
                    is TtsNavigator.State.Failure -> EngineStatus.Failed(sentenceFor(state.error))
                    // Ready, and the media navigator's Buffering: still a live session.
                    else -> EngineStatus.Ready
                }
            }
        }.stateIn(scope, SharingStarted.Eagerly, EngineStatus.Ready)

    override val utterance: StateFlow<String?> =
        navigator.location.map { it.utteranceLocator.toJSON().toString() }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun play() = navigator.play()

    override fun pause() = navigator.pause()

    override fun setSpeed(speed: Float) {
        navigator.submitPreferences(AndroidTtsPreferences(speed = speed.toDouble()))
    }

    override fun close() {
        ReadAloudSessions.close()
        navigator.close()
    }

    companion object {
        /** Builds the engine from the first visible sentence ([startLocatorJson]) of an EPUB. */
        suspend fun create(
            app: Application,
            publication: Publication,
            title: String,
            startLocatorJson: String?,
            scope: CoroutineScope,
        ): EngineResult {
            val factory = AndroidTtsNavigatorFactory(app, publication)
                ?: return EngineResult.Unavailable("This book has no text that can be read aloud.")
            val start = startLocatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
            val stopRequested = MutableStateFlow(false)
            val listener = object : TtsNavigator.Listener {
                // The notification or lock-screen stop: end the session rather than leave a dead one.
                override fun onStopRequested() {
                    stopRequested.value = true
                }
            }
            val navigator = factory.createNavigator(listener, initialLocator = start).getOrElse {
                return EngineResult.Unavailable(
                    "Reading aloud could not start: no text-to-speech engine answered. " +
                        "Check Android settings under Text-to-speech.",
                )
            }
            val engine = ReadiumReadAloudEngine(navigator, stopRequested, scope)
            ReadAloudSessions.open(app, navigator, title)
            return EngineResult.Ready(engine)
        }

        const val MISSING_VOICE = "The voice for this book's language is not installed. " +
            "Install it in Android settings under Text-to-speech, then try Listen again."

        private fun sentenceFor(error: TtsNavigator.Error): String = when (error) {
            is TtsNavigator.Error.ContentError -> "Reading aloud stopped: this part of the book has no readable text."
            is TtsNavigator.Error.EngineError<*> -> when (error.cause) {
                is AndroidTtsEngine.Error.LanguageMissingData -> MISSING_VOICE
                is AndroidTtsEngine.Error.Network ->
                    "Reading aloud stopped: the voice needs the network and none is available."
                else -> "Reading aloud stopped: the text-to-speech engine reported an error."
            }
        }
    }
}

/**
 * The one live read-aloud media session in the process. [BookReadAloudService] publishes it to the
 * system (notification, lock screen) and ends when it is closed. Held here rather than bound by
 * binder because both live in this process.
 */
@OptIn(ExperimentalReadiumApi::class)
@UnstableApi
object ReadAloudSessions {
    private val current = MutableStateFlow<MediaSession?>(null)
    val session: MediaSession? get() = current.value

    fun open(app: Application, navigator: AndroidTtsNavigator, title: String) {
        close()
        val session = MediaSession.Builder(app, navigator.asMedia3Player()).setId("books-read-aloud-$title").build()
        current.value = session
        app.startService(Intent(app, BookReadAloudService::class.java))
    }

    fun close() {
        current.value?.let { runCatching { it.release() } }
        current.value = null
    }

    fun stopService(context: Context) {
        context.stopService(Intent(context, BookReadAloudService::class.java))
    }
}

/** Publishes [ReadAloudSessions]' session so read-aloud survives the screen turning off. */
@UnstableApi
class BookReadAloudService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = ReadAloudSessions.session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ReadAloudSessions.session?.let { addSession(it) } ?: stopSelf(startId)
        // Never restarted by the system: a killed read-aloud is not resumed behind the user's back.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        ReadAloudSessions.close()
        stopSelf()
    }

    override fun onDestroy() {
        ReadAloudSessions.close()
        super.onDestroy()
    }
}
