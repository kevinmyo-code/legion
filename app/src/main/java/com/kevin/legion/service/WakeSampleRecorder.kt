package com.kevin.legion.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Date
import java.util.concurrent.Executors

/**
 * Records Kevin's own voice for wake-word tuning (ticket 20, `.scratch/wake-word/issues/20-*.md`).
 * Opt-in and off by default: nothing is written unless he turns live saving on or runs a take from
 * the enrollment screen. Files go to `Android/data/com.kevin.legion/files/wake-samples/` (the app's
 * external files dir) so a release build can be read with `adb pull`; nothing is uploaded. The file
 * layout is [WakeSampleFiles].
 *
 * It never opens a microphone. [WakeWordEngine] feeds it the chunks it already captures, so a sample
 * is exactly what the detector heard, through the same source and effects. Two records on one mic
 * in one process is the silenced-capture failure tickets 08 and 15 document.
 *
 * Writes run on their own thread: the capture loop and the detector's native thread never wait on
 * a file.
 */
@Suppress("TooManyFunctions") // one small entry point per engine and screen hook, nothing else
object WakeSampleRecorder {
    private const val TAG = "WakeSampleRecorder"

    /** What the enrollment screen shows about the newest take. */
    data class TakeResult(
        val label: String,
        val verdict: String,
        val heard: String?,
        val durationMs: Long,
        val peak: Int,
    )

    private val capture = WakeSampleCapture()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "wake-samples") }
    @Volatile private var root: File? = null
    @Volatile private var meta: Map<String, String> = emptyMap()
    private var lastTakeFiles: List<File> = emptyList()
    @Volatile private var sessionDir: String? = null

    /**
     * True while the enrollment screen is open: a wake hit is recorded and logged but does not open
     * a conversation (that would take the mic and end the session after the first good take).
     */
    @Volatile var enrolling: Boolean = false

    private val _lastTake = MutableStateFlow<TakeResult?>(null)
    val lastTake = _lastTake.asStateFlow()

    private val _takeOpen = MutableStateFlow(false)
    val takeOpen = _takeOpen.asStateFlow()

    /**
     * Called when capture opens: where to write, whether live saving is on, and what to stamp.
     * [twoStage] false means the Vosk-only fallback is listening, which emits no stage signals, so
     * the eval can tell an empty event list from a miss.
     */
    fun configure(context: Context, source: String, effects: Int, twoStage: Boolean) {
        root = context.getExternalFilesDir(WakeSampleFiles.DIR)
        capture.liveOn = WakeWordPreferences.saveLiveSamples(context)
        meta = mapOf("source" to source, "effects" to effects.toString(), "twoStage" to twoStage.toString())
    }

    fun setLiveOn(on: Boolean) {
        capture.liveOn = on
    }

    fun onAudio(buf: ShortArray, n: Int) = settle(capture.onAudio(buf, n))

    fun onSignal(signal: WakeSignal) = settle(capture.onSignal(signal, stamped()))

    /** Capture is closing: write whatever was in progress (the hit that tore it down included). */
    fun flush() = settle(capture.flush())

    /** Starts one enrollment session folder; takes after this land in it. */
    fun beginSession() {
        sessionDir = "enroll-" + WakeSampleFiles.stamp()
    }

    fun beginTake(label: String, maxMs: Long) {
        settle(capture.beginTake(label, maxMs, stamped()))
    }

    fun endTake() = settle(listOfNotNull(capture.endTake()))

    /** True once the take the screen started has closed (by its length cap or [endTake]). */
    fun takeStillOpen(): Boolean = capture.takeOpen

    /** "Redo": the newest take's files are deleted so a botched one never reaches the eval. */
    fun deleteLastTake() {
        writer.execute {
            lastTakeFiles.forEach { runCatching { it.delete() } }
            lastTakeFiles = emptyList()
        }
        _lastTake.value = null
    }

    /** Every saved sample, enrollment and live, deleted. */
    fun deleteAll(context: Context) {
        writer.execute {
            runCatching { context.getExternalFilesDir(WakeSampleFiles.DIR)?.deleteRecursively() }
        }
        _lastTake.value = null
    }

    /** WAV count on disk, for the screen. Reads the filesystem; call off the main thread. */
    fun savedCount(context: Context): Int = WakeSampleFiles.count(context.getExternalFilesDir(WakeSampleFiles.DIR))

    private fun stamped(): Map<String, String> = meta + mapOf("wallClock" to Date().toString())

    /**
     * Mirrors the capture's take state into [takeOpen] right away, on the caller's thread, so a take
     * that closed is never shown as still recording because its write failed or had nowhere to go.
     */
    private fun settle(done: List<WakeSampleCapture.Sample>) {
        _takeOpen.value = capture.takeOpen
        if (done.isEmpty()) return
        val base = root
        if (base == null) {
            Log.w(TAG, "no files dir yet - ${done.size} sample(s) dropped")
            return
        }
        val session = sessionDir
        writer.execute {
            for (s in done) {
                runCatching { onWritten(s, WakeSampleFiles.write(base, session, s)) }
                    .onFailure { Log.w(TAG, "write failed: ${it.message}") }
            }
        }
    }

    private fun onWritten(s: WakeSampleCapture.Sample, files: List<File>) {
        if (s.kind != "take") return
        lastTakeFiles = files
        val heard = if (s.sidecar.isNull("confirmText")) null else s.sidecar.optString("confirmText")
        _lastTake.value = TakeResult(
            label = s.label,
            verdict = s.sidecar.optString("verdict"),
            heard = heard,
            durationMs = s.sidecar.optLong("durationMs"),
            peak = s.sidecar.optInt("peak"),
        )
    }
}
