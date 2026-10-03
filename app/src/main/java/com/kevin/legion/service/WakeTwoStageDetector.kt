package com.kevin.legion.service

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * The audio half of the two-stage wake detector (ticket 18, Kevin 2026-10-03: "how siri does it"):
 * stage 0 Silero VAD, stage 1 sherpa-onnx keyword spotting, both driven through
 * [WakeStageMachine]. Stage 2 (the Vosk confirm) is passed in as a lambda so this class owns no
 * Vosk and the engine keeps owning the microphone and the recognizer.
 *
 * **The thresholds below are GUESSES** (library defaults for the VAD, the KWS model's documented
 * defaults), not measurements. The A25 run in the ticket is what calibrates them.
 */
class WakeTwoStageDetector private constructor(
    private val vad: Vad,
    private val spotter: KeywordSpotter,
    private val stream: OnlineStream,
    private val machine: WakeStageMachine,
) {
    // The last CONFIRM_SECONDS of audio, as a ring. Written every chunk, including silence: the
    // confirm window must contain the phrase's start, which arrives before the VAD flips on.
    private val ring = ShortArray(SAMPLE_RATE * CONFIRM_SECONDS)
    private var ringWritten = 0L

    /**
     * Feeds one captured chunk. Returns true when stage 1 AND stage 2 agree and the caller should
     * open the session. [confirm] receives the buffered audio (oldest first) and answers whether
     * the Vosk grammar hears the wake phrase in it.
     */
    fun process(buf: ShortArray, n: Int, confirm: (ShortArray) -> Boolean): Boolean {
        push(buf, n)
        val floats = FloatArray(n) { buf[it] / SHORT_SCALE }
        vad.acceptWaveform(floats)
        val speech = vad.isSpeechDetected()
        // Segments are not used (only the flag is); drain so the VAD queue stays empty.
        while (!vad.empty()) vad.pop()

        val wasInSpeech = machine.inSpeech
        val step = machine.onChunk(speech) {
            // Lead-in: the VAD flips on a few hundred ms into the phrase, so on entry the spotter
            // is handed the audio just before it too, else "hey" would be missing.
            val feed = if (wasInSpeech) floats else toFloats(tail(PREROLL_SAMPLES + n))
            stream.acceptWaveform(feed, SAMPLE_RATE)
            while (spotter.isReady(stream)) spotter.decode(stream)
            val hit = spotter.getResult(stream).keyword.isNotBlank()
            if (hit) spotter.reset(stream)
            hit
        }
        if (wasInSpeech && !machine.inSpeech) spotter.reset(stream)
        if (step !is WakeStageMachine.Step.Candidate) return false
        val accepted = confirm(tail(ring.size))
        return machine.onConfirm(accepted) == WakeStageMachine.Verdict.OPEN
    }

    fun release() {
        runCatching { stream.release() }
        runCatching { spotter.release() }
        runCatching { vad.release() }
    }

    private fun push(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[((ringWritten + i) % ring.size).toInt()] = buf[i]
        }
        ringWritten += n
    }

    /** The most recent [count] samples, oldest first (fewer if less has been captured). */
    private fun tail(count: Int): ShortArray {
        val have = minOf(count.toLong(), ringWritten, ring.size.toLong()).toInt()
        val start = ringWritten - have
        return ShortArray(have) { ring[((start + it) % ring.size).toInt()] }
    }

    private fun toFloats(s: ShortArray): FloatArray = FloatArray(s.size) { s[it] / SHORT_SCALE }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SHORT_SCALE = 32768f
        private const val CONFIRM_SECONDS = 2
        private const val PREROLL_SAMPLES = SAMPLE_RATE / 2
        private const val ASSET_DIR = "wake-kws"
        private const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private val MODEL_FILES = listOf(ENCODER, DECODER, JOINER, "tokens.txt", "bpe.model", "silero_vad.onnx")

        sealed interface Created {
            data class Ready(val detector: WakeTwoStageDetector) : Created
            data class Unavailable(val reason: String) : Created
        }

        /**
         * Builds the pipeline for [companionName], or says in words why it cannot (the engine then
         * falls back to Vosk-only and records that, rather than listening to nothing).
         */
        // UnsatisfiedLinkError is an Error: a missing ABI lib must degrade, not crash the service.
        // Early returns carry the refuse-in-words results.
        @Suppress("TooGenericExceptionCaught", "ReturnCount", "NestedBlockDepth") // see the line above
        fun create(context: Context, companionName: String, log: (String) -> Unit): Created {
            val dir = File(context.filesDir, ASSET_DIR)
            try {
                dir.mkdirs()
                for (name in MODEL_FILES) {
                    val dest = File(dir, name)
                    if (!dest.exists()) {
                        context.assets.open("$ASSET_DIR/$name").use { i ->
                            dest.outputStream().use { o -> i.copyTo(o) }
                        }
                    }
                }
                val tokenizer = BpeKeywordTokenizer.fromModel(File(dir, "bpe.model").readBytes())
                val keywords = when (val r = WakeKeywords.build(companionName, tokenizer)) {
                    is WakeKeywords.Result.Ok -> r.fileText
                    is WakeKeywords.Result.Refused -> return Created.Unavailable(r.reason)
                }
                val keywordsFile = File(dir, "keywords.txt").also { it.writeText(keywords) }
                fun p(name: String) = File(dir, name).absolutePath

                val kwsConfig = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = p(ENCODER), decoder = p(DECODER), joiner = p(JOINER),
                        ),
                        tokens = p("tokens.txt"),
                        numThreads = 1,
                        modelType = "zipformer2",
                    ),
                    maxActivePaths = 4,
                    keywordsFile = keywordsFile.absolutePath,
                    keywordsScore = 1.0f,
                    keywordsThreshold = 0.25f,
                    numTrailingBlanks = 1,
                )
                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = p("silero_vad.onnx"),
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.1f,
                        windowSize = 512,
                        maxSpeechDuration = 20f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                )
                val vad = Vad(null, vadConfig)
                val spotter = KeywordSpotter(null, kwsConfig)
                val stream = spotter.createStream()
                log("two-stage ready: keywords=" + keywords.trim().lines().joinToString(" | "))
                return Created.Ready(WakeTwoStageDetector(vad, spotter, stream, WakeStageMachine(log)))
            } catch (e: Throwable) {
                return Created.Unavailable("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
