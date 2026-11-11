package com.kevin.legion.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer

/**
 * The openWakeWord wake detector (Kevin 2026-11-10: "i rather openwakeword, current one doesnt work
 * as well"), the alternative to [WakeTwoStageDetector] chosen by [WakeWordPreferences.engine].
 *
 * One model chain on the ONNX Runtime the app already ships (no new native library): melspectrogram,
 * the shared speech embedding, and a head trained on the laptop for "hey <companion name>"
 * (`C:\Users\kevin\.legion\reports\openwakeword-1110.md`). [OwwStream] owns the buffering and
 * [OwwDecider] the threshold and trigger gap; this class only binds them to the models and logs.
 *
 * **Threading, same rule as the keyword spotter (see [WakeCaptureGate]).** The engine builds this
 * under the gate's lock, calls [process] only inside `gate.whileOpen`, and calls [release] only
 * after `gate.close()`, so no session is closed while a chunk is running. The shared
 * [OrtEnvironment] is never closed: the turn models use it too.
 */
class OpenWakeWordDetector internal constructor(
    private val models: OwwModels,
    val threshold: Float,
    private val log: (String) -> Unit,
) {
    private val stream = OwwStream(models, models, models)
    private val decider = OwwDecider(threshold)

    /**
     * Feeds one captured chunk ([n] samples of [buf], 16 kHz int16). True when a frame in it crossed
     * the threshold outside the trigger gap and the caller should open the conversation. Each open
     * and each near miss is one `wake_stage` line in [decisionLine]'s format.
     */
    fun process(buf: ShortArray, n: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        var open = false
        for (score in stream.feed(buf, n)) {
            when (val d = decider.onScore(score, nowMs)) {
                is OwwDecider.Decision.Open -> {
                    log(decisionLine("open", d.score, threshold))
                    open = true
                }
                is OwwDecider.Decision.NearMiss -> log(decisionLine("below", d.score, threshold))
                null -> Unit
            }
        }
        return open
    }

    fun release() {
        runCatching { models.close() }
    }

    companion object {
        const val ENGINE = "openwakeword"

        /**
         * Starting threshold. openWakeWord's README default is 0.5; the laptop sweep on synthetic
         * positives and open negative sets is in the report. Real-voice numbers are owed.
         */
        const val DEFAULT_THRESHOLD = 0.5f

        /**
         * Input gain for this engine: 1, the raw capture. The keyword spotter's x4 ([WAKE_INPUT_GAIN])
         * was tuned for that model; openWakeWord was trained on level-augmented audio and x4 clips
         * loud speech, which it never saw in training. Logged at start so a log says which it heard.
         */
        const val INPUT_GAIN = 1

        /** One wake decision, comparable across engines: `decision engine=... verdict=... score=...`. */
        fun decisionLine(verdict: String, score: Float, threshold: Float): String =
            "decision engine=$ENGINE verdict=$verdict score=" + "%.3f".format(java.util.Locale.ROOT, score) +
                " threshold=" + "%.2f".format(java.util.Locale.ROOT, threshold) + " gain=$INPUT_GAIN"

        sealed interface Created {
            data class Ready(val detector: OpenWakeWordDetector) : Created
            data class Unavailable(val reason: String) : Created
        }

        /**
         * Builds the detector for [companionName], or says in words why it cannot (the engine then
         * falls back to the current keyword spotter and says so).
         */
        // Any native or file failure must degrade to the other engine, never crash the service.
        @Suppress("TooGenericExceptionCaught")
        fun create(context: Context, companionName: String, threshold: Float, log: (String) -> Unit): Created {
            val files = when (val r = OwwModelFiles.load(context, companionName)) {
                is OwwModelFiles.Loaded.Missing -> return Created.Unavailable(r.reason)
                is OwwModelFiles.Loaded.Found -> r
            }
            return try {
                val models = OwwOrtModels(OrtEnvironment.getEnvironment(), files.mel, files.embedding, files.head)
                log(
                    "openwakeword ready: head=" + files.headName + " from " + files.source +
                        " frames=" + models.inputFrames + " threshold=" + "%.2f".format(java.util.Locale.ROOT, threshold) +
                        " gain=" + INPUT_GAIN,
                )
                Created.Ready(OpenWakeWordDetector(models, threshold, log))
            } catch (t: Throwable) {
                Created.Unavailable("${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}

/**
 * Where the three model files come from. None is in git (licences: the report). In order:
 * 1. the app's external files dir, `Android/data/com.kevin.legion/files/openwakeword/`, so a head
 *    retrained on the laptop can be dropped in with `adb push` and no rebuild;
 * 2. `assets/openwakeword/`, gitignored, filled on the build machine before `assembleFast`.
 * Both are private to this app. A missing file is a refusal in words, never a silent deaf engine.
 */
object OwwModelFiles {
    const val DIR = "openwakeword"
    const val MEL = "melspectrogram.onnx"
    const val EMBEDDING = "embedding_model.onnx"

    sealed interface Loaded {
        class Found(
            val mel: ByteArray,
            val embedding: ByteArray,
            val head: ByteArray,
            val headName: String,
            val source: String,
        ) : Loaded

        data class Missing(val reason: String) : Loaded
    }

    /** "hey alfred" -> `hey_alfred.onnx`; null for a blank name (no phrase, nothing to load). */
    fun headName(companionName: String): String? =
        WakePhrases.grammar(companionName).firstOrNull()?.let { it.replace(' ', '_') + ".onnx" }

    fun load(context: Context, companionName: String): Loaded {
        val head = headName(companionName) ?: return Loaded.Missing("no companion name - no wake phrase")
        val names = listOf(MEL, EMBEDDING, head)
        context.getExternalFilesDir(DIR)?.let { dir ->
            if (names.all { File(dir, it).isFile }) {
                return Loaded.Found(
                    File(dir, MEL).readBytes(), File(dir, EMBEDDING).readBytes(), File(dir, head).readBytes(),
                    head, "external files",
                )
            }
        }
        val bundled = runCatching { context.assets.list(DIR)?.toSet() }.getOrNull().orEmpty()
        val missing = names.filterNot { it in bundled }
        if (missing.isNotEmpty()) {
            return Loaded.Missing("openWakeWord model missing: " + missing.joinToString(", "))
        }
        fun asset(name: String) = context.assets.open("$DIR/$name").use { it.readBytes() }
        return Loaded.Found(asset(MEL), asset(EMBEDDING), asset(head), head, "app assets")
    }
}

/** The three models [OpenWakeWordDetector] runs and frees together; a fake in tests. */
internal interface OwwModels : OwwMel, OwwEmbedding, OwwHead, AutoCloseable

/** The three ONNX sessions behind [OwwMel], [OwwEmbedding] and [OwwHead]. */
internal class OwwOrtModels(
    private val env: OrtEnvironment,
    melBytes: ByteArray,
    embeddingBytes: ByteArray,
    headBytes: ByteArray,
) : OwwModels {
    private val mel = session(melBytes)
    private val embedding = session(embeddingBytes)
    private val head = session(headBytes)
    private val headInput = head.inputNames.first()

    override val inputFrames: Int = run {
        val info = head.inputInfo.getValue(headInput).info as ai.onnxruntime.TensorInfo
        val frames = info.shape.getOrNull(1) ?: DEFAULT_FRAMES
        if (frames > 0) frames.toInt() else DEFAULT_FRAMES.toInt()
    }

    private fun session(bytes: ByteArray): OrtSession = OrtSession.SessionOptions().use { opts ->
        opts.setIntraOpNumThreads(1)
        opts.setInterOpNumThreads(1)
        env.createSession(bytes, opts)
    }

    override fun mel(samples: FloatArray): Array<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { x ->
            mel.run(mapOf("input" to x)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return (r.get(0).value as Array<Array<Array<FloatArray>>>)[0][0]
            }
        }
    }

    override fun embed(window: Array<FloatArray>): FloatArray {
        val flat = FloatArray(window.size * OwwStream.MEL_BINS)
        for (i in window.indices) System.arraycopy(window[i], 0, flat, i * OwwStream.MEL_BINS, OwwStream.MEL_BINS)
        val shape = longArrayOf(1, window.size.toLong(), OwwStream.MEL_BINS.toLong(), 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { x ->
            embedding.run(mapOf("input_1" to x)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return (r.get(0).value as Array<Array<Array<FloatArray>>>)[0][0][0]
            }
        }
    }

    override fun score(features: Array<FloatArray>): Float {
        val dim = OwwStream.EMBEDDING_DIM
        val flat = FloatArray(features.size * dim)
        for (i in features.indices) System.arraycopy(features[i], 0, flat, i * dim, dim)
        val shape = longArrayOf(1, features.size.toLong(), dim.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { x ->
            head.run(mapOf(headInput to x)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return (r.get(0).value as Array<FloatArray>)[0][0]
            }
        }
    }

    override fun close() {
        runCatching { mel.close() }
        runCatching { embedding.close() }
        runCatching { head.close() }
    }

    private companion object {
        const val DEFAULT_FRAMES = 16L
    }
}
