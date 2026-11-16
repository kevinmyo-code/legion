package com.kevin.legion.service.speaker

import android.content.Context
import com.kevin.legion.service.LiveLatency
import com.kevin.legion.service.WakeSampleFiles
import com.kevin.legion.service.WakeSampleRecorder
import com.kevin.legion.service.WakeSignal
import com.kevin.legion.service.WakeWordPreferences
import com.kevin.legion.service.cascade.CascadeWarmup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The Android half of "only my voice": the audio ring the wake engine feeds, the model and the
 * voiceprint, and the one call the engine makes on a wake hit ([allowOpen]). The rules are
 * [SpeakerPolicy]; the scoring is [SpeakerChecker].
 *
 * Privacy, binding: audio and the voiceprint stay on the phone. The voiceprint is one small file in
 * the app's private files dir ([VOICEPRINT_DIR], excluded from backup and device transfer); the
 * log carries scores, decisions and timings only.
 */
@Suppress("TooManyFunctions") // engine hooks and screen hooks, each one small
object SpeakerVerifier {
    const val VOICEPRINT_DIR = "voiceprint"
    private const val VOICEPRINT_FILE = "voiceprint.bin"

    /** What the screen shows about the stored voiceprint. */
    data class Info(val takes: Int, val builtAtMs: Long)

    /** One enrolment session on disk and how many of its takes would count. */
    data class Session(val name: String, val usableTakes: Int)

    // The one thread the model is loaded and run on, so a compute and a load never overlap.
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "speaker-check") }
    private val ring = SpeakerRing(SpeakerMath.samples(SpeakerPolicy.SEARCH_MS))

    @Volatile private var embedderState: EmbedderState = EmbedderState.Missing
    @Volatile private var loadAttempted = false
    @Volatile private var voiceprint: FloatArray? = null

    private val checker = SpeakerChecker(worker, { embedderState }, { voiceprint })

    private val _info = MutableStateFlow<Info?>(null)
    val info = _info.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)

    /** The last enrolment or model message, in words, for the screen. */
    val note = _note.asStateFlow()

    // --- Engine hooks ------------------------------------------------------------------------

    /** Wake engine start: read the voiceprint, and load the model only if there is one to use. */
    fun prepare(context: Context) {
        val app = context.applicationContext
        worker.execute {
            readVoiceprint(app)
            if (voiceprint != null) loadModel(app)
        }
    }

    /** Every captured chunk, raw (before any detector gain), so the window is what the mic heard. */
    fun onAudio(buf: ShortArray, n: Int) = ring.push(buf, n)

    /** A fresh capture: audio from before a conversation must not end up in the next window. */
    fun resetAudio() = ring.reset()

    /**
     * The wake engine's hit. True: open as today. False: held back, and this has already done what a
     * held wake does (the tap prompt for an unsure score, the log, the sample signal). Shadow mode
     * always returns true without waiting; gated waits at most [SpeakerPolicy.CHECK_DEADLINE_MS].
     */
    // Runs on the wake capture loop: a throw there would end the loop and leave the wake word deaf,
    // so anything unexpected opens as today and says so.
    @Suppress("TooGenericExceptionCaught")
    fun allowOpen(context: Context): Boolean = try {
        decide(context)
    } catch (t: Throwable) {
        LiveLatency.log(LiveLatency.TAG, "speaker_check result=failed_${t.javaClass.simpleName} acted=open")
        true
    }

    @Suppress("ReturnCount") // shadow opens, a gated open opens, a held wake does not
    private fun decide(context: Context): Boolean {
        val mode = SpeakerPolicy.mode(WakeWordPreferences.onlyMyVoice(context))
        val window = SpeakerWindow.pick(ring.snapshot())
        if (mode == SpeakerPolicy.Mode.SHADOW) {
            checker.shadow(window) { r -> log(mode, r) }
            return true
        }
        val result = checker.gated(window)
        val acted = log(mode, result)
        if (acted == SpeakerPolicy.Band.OPEN) return true
        WakeSampleRecorder.onSignal(WakeSignal.SpeakerHeld(acted.word))
        CascadeWarmup.onWakeMissed()
        if (acted == SpeakerPolicy.Band.ASK) SpeakerAskNotifier.post(context)
        return false
    }

    private fun log(mode: SpeakerPolicy.Mode, result: SpeakerResult): SpeakerPolicy.Band {
        val acted = SpeakerPolicy.act(mode, result)
        LiveLatency.log(LiveLatency.TAG, SpeakerPolicy.line(mode, result, acted))
        return acted
    }

    // --- Screen hooks ------------------------------------------------------------------------

    /** Reads the stored voiceprint's summary into [info]. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        worker.execute { readVoiceprint(app) }
    }

    /** Enrolment sessions on disk, newest first. Reads files: call off the main thread. */
    fun sessions(context: Context): List<Session> =
        sessionDirs(context).map { dir ->
            Session(dir.name, takes(dir).count { (side, _) -> SpeakerEnrolment.eligible(side) })
        }

    /**
     * Builds the voiceprint from the confirmed wake takes of [sessionNames], replacing any earlier
     * one. The result goes to [note] as counts only.
     */
    fun build(context: Context, sessionNames: Set<String>) {
        val app = context.applicationContext
        _note.value = "Building..."
        worker.execute { _note.value = buildSafely(app, sessionNames) }
    }

    /** Deletes the voiceprint and turns "Only my voice" off with it. */
    fun delete(context: Context) {
        val app = context.applicationContext
        WakeWordPreferences.setOnlyMyVoice(app, false)
        worker.execute {
            voiceprintFile(app).delete()
            voiceprint = null
            _info.value = null
            _note.value = "Voiceprint deleted."
        }
    }

    // --- Worker-thread internals -------------------------------------------------------------

    // Any failure to load is "model missing" in words; the wake word must keep working.
    @Suppress("TooGenericExceptionCaught")
    private fun loadModel(app: Context, retry: Boolean = false) {
        if (embedderState is EmbedderState.Ready) return
        if (loadAttempted && !retry) return
        loadAttempted = true
        embedderState = EmbedderState.Loading
        val t0 = System.currentTimeMillis()
        embedderState = try {
            SherpaSpeakerEmbedder.load(app)?.let { EmbedderState.Ready(it) } ?: EmbedderState.Missing
        } catch (t: Throwable) {
            LiveLatency.log(LiveLatency.TAG, "speaker_model result=failed_${t.javaClass.simpleName}")
            EmbedderState.Missing
        }
        val word = if (embedderState is EmbedderState.Ready) "ready" else "missing"
        LiveLatency.log(LiveLatency.TAG, "speaker_model result=$word load=${System.currentTimeMillis() - t0}")
    }

    private fun readVoiceprint(app: Context) {
        val file = voiceprintFile(app)
        val v = if (file.isFile) runCatching { VoiceprintCodec.decode(file.readBytes()) }.getOrNull() else null
        voiceprint = v?.vector
        _info.value = v?.let { Info(it.takes, it.builtAtMs) }
    }

    // A file or model failure mid-build (say, recordings deleted meanwhile) is a sentence on the
    // screen, never an uncaught throw on this thread.
    @Suppress("TooGenericExceptionCaught")
    private fun buildSafely(app: Context, sessionNames: Set<String>): String = try {
        buildNow(app, sessionNames)
    } catch (t: Throwable) {
        "Could not build the voiceprint (${t.javaClass.simpleName}). Nothing was saved."
    }

    @Suppress("ReturnCount") // two refusals in words, then the build
    private fun buildNow(app: Context, sessionNames: Set<String>): String {
        if (sessionNames.isEmpty()) return "Choose at least one session."
        // An explicit build tries again, so a model pushed after a miss is found without a restart.
        loadModel(app, retry = true)
        val embedder = (embedderState as? EmbedderState.Ready)?.embedder
            ?: return "The speaker model is not on this phone, so no voiceprint can be built."
        val windows = sessionDirs(app).filter { it.name in sessionNames }
            .flatMap { takes(it) }
            .mapNotNull { (side, wav) -> WavReader.pcm16(wav.readBytes())?.let { SpeakerEnrolment.window(side, it) } }
        return when (val built = SpeakerEnrolment.build(windows) { embedder.embedWindow(it) }) {
            is SpeakerEnrolment.Built.TooFew ->
                "Only ${built.usable} usable takes; a voiceprint needs ${SpeakerPolicy.MIN_ENROL_TAKES}."
            SpeakerEnrolment.Built.Failed -> "The model gave unusable output; no voiceprint saved."
            is SpeakerEnrolment.Built.Ready -> {
                val vp = VoiceprintCodec.Voiceprint(built.voiceprint, built.takes, System.currentTimeMillis())
                val file = voiceprintFile(app)
                file.parentFile?.mkdirs()
                file.writeBytes(VoiceprintCodec.encode(vp))
                voiceprint = vp.vector
                _info.value = Info(vp.takes, vp.builtAtMs)
                LiveLatency.log(LiveLatency.TAG, "speaker_enrol result=built takes=${vp.takes}")
                "Voiceprint built from ${vp.takes} takes."
            }
        }
    }

    private fun voiceprintFile(context: Context) = File(File(context.filesDir, VOICEPRINT_DIR), VOICEPRINT_FILE)

    private fun sessionDirs(context: Context): List<File> =
        context.getExternalFilesDir(WakeSampleFiles.DIR)
            ?.listFiles { f -> f.isDirectory && f.name.startsWith("enroll-") }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** Each take's sidecar and its WAV, for the takes that have both. */
    private fun takes(dir: File): List<Pair<JSONObject, File>> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { json ->
            val wav = File(dir, json.nameWithoutExtension + ".wav")
            val side = runCatching { JSONObject(json.readText()) }.getOrNull()
            if (side != null && wav.isFile) side to wav else null
        }
}
