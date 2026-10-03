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
import java.util.concurrent.Callable
import java.util.concurrent.Executors

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
    private var stream: OnlineStream,
    private val machine: WakeStageMachine,
    private val breaker: TwoStageBreaker,
) {
    // The last CONFIRM_SECONDS of audio, as a ring. Written every chunk, including silence: the
    // confirm window must contain the phrase's start, which arrives before the VAD flips on.
    private val ring = ShortArray(SAMPLE_RATE * CONFIRM_SECONDS)
    private var ringWritten = 0L

    // Every native call (VAD, spotter, release) runs on this ONE thread. A native abort cannot be
    // caught, and release() racing process() from another thread is the one way this class could
    // hand the library a torn state, so they are serialised rather than trusted to be sequential.
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "wake-two-stage") }

    private val port = object : KeywordStreamPort {
        override fun accept(samples: FloatArray) = stream.acceptWaveform(samples, SAMPLE_RATE)
        override fun isReady() = spotter.isReady(stream)
        override fun decode() = spotter.decode(stream)
        override fun keyword(): String = spotter.getResult(stream).keyword
        override fun fresh() {
            runCatching { stream.release() }
            stream = spotter.createStream()
        }
    }

    /**
     * Feeds one captured chunk. Returns true when stage 1 AND stage 2 agree and the caller should
     * open the session. [confirm] receives the buffered audio (oldest first) and answers whether
     * the Vosk grammar hears the wake phrase in it.
     */
    fun process(buf: ShortArray, n: Int, confirm: (ShortArray) -> Boolean): Boolean =
        executor.submit(Callable { processOnWorker(buf, n, confirm) }).get()

    private fun processOnWorker(buf: ShortArray, n: Int, confirm: (ShortArray) -> Boolean): Boolean {
        push(buf, n)
        val floats = FloatArray(n) { buf[it] / SHORT_SCALE }
        vad.acceptWaveform(floats)
        val speech = vad.isSpeechDetected()
        // Segments are not used (only the flag is); drain so the VAD queue stays empty.
        while (!vad.empty()) vad.pop()

        val wasInSpeech = machine.inSpeech
        val step = machine.onChunk(speech) {
            // A new utterance gets a CLEAN stream, fed the lead-in first: the VAD flips on a few
            // hundred ms into the phrase, so without the lead-in "hey" would be missing.
            val feed = if (wasInSpeech) {
                floats
            } else {
                port.fresh()
                toFloats(tail(PREROLL_SAMPLES + n))
            }
            // Marker only around the KWS (the native code that has crashed), so silence never
            // leaves it set for a force-stop to mistake for a crash.
            breaker.markStarted()
            KwsFeeder.feed(port, feed).also { breaker.onDecodeOk() }
        }
        // Speech ended: drop the stream rather than decode its partial tail.
        if (wasInSpeech && !machine.inSpeech) port.fresh()
        if (step !is WakeStageMachine.Step.Candidate) return false
        val accepted = confirm(tail(ring.size))
        return machine.onConfirm(accepted) == WakeStageMachine.Verdict.OPEN
    }

    fun release() {
        // Queued behind any in-flight process() on the same thread, then the thread ends.
        runCatching {
            executor.submit {
                runCatching { stream.release() }
                runCatching { spotter.release() }
                runCatching { vad.release() }
                breaker.onCleanRelease()
            }.get()
        }
        executor.shutdown()
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
        @Suppress("TooGenericExceptionCaught", "ReturnCount", "NestedBlockDepth", "LongMethod") // see the line above
        fun create(context: Context, companionName: String, log: (String) -> Unit): Created {
            val dir = File(context.filesDir, ASSET_DIR)
            val breaker = TwoStageBreaker(object : TwoStageBreaker.Store {
                override var markerSet: Boolean
                    get() = WakeWordPreferences.twoStageMarker(context)
                    set(v) = WakeWordPreferences.setTwoStageMarker(context, v)
                override var tripped: Boolean
                    get() = WakeWordPreferences.twoStageTripped(context)
                    set(v) = WakeWordPreferences.setTwoStageTripped(context, v)
            })
            if (!breaker.shouldStart()) {
                WakeWordPreferences.setUseTwoStage(context, false)
                // setUseTwoStage(false) leaves the tripped flag alone; the Settings row reads it.
                return Created.Unavailable("the previous run died inside the detector - turned off after a crash")
            }
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
                return Created.Ready(WakeTwoStageDetector(vad, spotter, stream, WakeStageMachine(log), breaker))
            } catch (e: Throwable) {
                return Created.Unavailable("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
