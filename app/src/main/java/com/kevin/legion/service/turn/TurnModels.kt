package com.kevin.legion.service.turn

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Speech probability for one 512-sample (32 ms) window at 16 kHz. Stateful across calls. */
internal interface SpeechProbStream {
    fun prob(window: FloatArray): Float
    fun reset()
}

/** Probability (0..1) that the turn in [features] ([WhisperLogMel] layout) is finished. */
internal fun interface TurnEndModel {
    fun probability(features: FloatArray): Float
}

/**
 * Silero VAD v6.2 on ONNX Runtime. The session is shared; each [stream] carries its own LSTM state
 * and 64-sample context, exactly as `silero_vad.utils_vad.OnnxWrapper` feeds it (input `[1, 576]`
 * = context + window, `state [2, 1, 128]`, `sr` int64).
 */
internal class SileroVad(private val env: OrtEnvironment, modelBytes: ByteArray) : AutoCloseable {
    private val session: OrtSession = OrtSession.SessionOptions().use { opts ->
        opts.setIntraOpNumThreads(1)
        opts.setInterOpNumThreads(1)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(modelBytes, opts)
    }

    fun stream(): SpeechProbStream = Stream()

    override fun close() = session.close()

    private inner class Stream : SpeechProbStream {
        private val state = FloatArray(STATE_SIZE)
        private val input = FloatArray(CONTEXT + WINDOW)

        override fun reset() {
            state.fill(0f)
            input.fill(0f)
        }

        override fun prob(window: FloatArray): Float {
            require(window.size == WINDOW) { "Silero window must be $WINDOW samples" }
            System.arraycopy(window, 0, input, CONTEXT, WINDOW)
            val x = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + WINDOW).toLong()))
            val st = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
            val sr = OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(longArrayOf(16_000L)), longArrayOf())
            try {
                session.run(mapOf("input" to x, "state" to st, "sr" to sr)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val p = (r.get(0).value as Array<FloatArray>)[0][0]
                    @Suppress("UNCHECKED_CAST")
                    val next = r.get(1).value as Array<Array<FloatArray>>
                    var i = 0
                    for (a in next) for (b in a) for (v in b) state[i++] = v
                    // Context for the next window is the tail of this input.
                    System.arraycopy(input, WINDOW, input, 0, CONTEXT)
                    return p
                }
            } finally {
                x.close(); st.close(); sr.close()
            }
        }
    }

    companion object {
        const val WINDOW = 512
        const val CONTEXT = 64
        private const val STATE_SIZE = 2 * 1 * 128
    }
}

/** Pipecat Smart Turn v3.2 (cpu, int8) on ONNX Runtime. Input `input_features [1, 80, 800]`. */
internal class SmartTurnModel(
    private val env: OrtEnvironment,
    modelBytes: ByteArray,
    threads: Int = INFERENCE_THREADS,
) : TurnEndModel, AutoCloseable {
    private val session: OrtSession = OrtSession.SessionOptions().use { opts ->
        opts.setIntraOpNumThreads(threads)
        opts.setInterOpNumThreads(1)
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(modelBytes, opts)
    }

    override fun probability(features: FloatArray): Float {
        val t = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(features),
            longArrayOf(1, WhisperLogMel.N_MELS.toLong(), WhisperLogMel.N_FRAMES.toLong()),
        )
        try {
            session.run(mapOf("input_features" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return (r.get(0).value as Array<FloatArray>)[0][0]
            }
        } finally {
            t.close()
        }
    }

    override fun close() = session.close()

    companion object {
        /** Section 11 of the turn-taking design: 2 intra-op threads on the A25's big cores. */
        const val INFERENCE_THREADS = 2
    }
}

/**
 * Process-wide holder for the two turn models, loaded once from `assets/turn/` (bundled, never
 * fetched: CLAUDE.md sec 7) on a background thread. A session that wants local end of turn calls
 * [awaitReady]; a model that failed to load, or later threw, marks the whole process [broken], and
 * every later session falls back to server VAD (said in words once by the controller).
 */
internal object TurnModels {
    private const val TAG = "TurnModels"
    const val SILERO_ASSET = "turn/silero_vad_v6_2.onnx"
    const val SMART_TURN_ASSET = "turn/smart-turn-v3.2-cpu.onnx"

    sealed interface State {
        data object NotStarted : State
        data object Loading : State
        data class Ready(val silero: SileroVad, val smartTurn: SmartTurnModel, val loadMs: Long) : State
        data class Failed(val reason: String) : State
    }

    @Volatile var state: State = State.NotStarted
        private set
    private val loaded = CountDownLatch(1)

    /** Why local turn detection is off for the rest of this process, null while it is usable. */
    @Volatile var broken: String? = null
        private set

    fun markBroken(reason: String) {
        if (broken == null) {
            broken = reason
            Log.w(TAG, "local turn detection disabled for this process: $reason")
        }
    }

    /** Starts the load if it has not started. Cheap to call repeatedly. */
    @Synchronized
    fun preload(context: Context) {
        if (state != State.NotStarted) return
        state = State.Loading
        val app = context.applicationContext
        Thread({
            val started = System.currentTimeMillis()
            // Assigned before the latch opens: a `state = try { } finally { countDown() }` opens it
            // first, and a waiter then reads Loading (seen on the A25, 2026-10-27).
            val result: State = try {
                val env = OrtEnvironment.getEnvironment()
                val silero = SileroVad(env, app.assets.open(SILERO_ASSET).use { it.readBytes() })
                val smart = SmartTurnModel(env, app.assets.open(SMART_TURN_ASSET).use { it.readBytes() })
                val ms = System.currentTimeMillis() - started
                Log.i(TAG, "turn models loaded in ${ms}ms")
                State.Ready(silero, smart, ms)
            } catch (t: Throwable) {
                Log.w(TAG, "turn models failed to load: ${t.message}", t)
                markBroken("model load failed: ${t.javaClass.simpleName}: ${t.message}")
                State.Failed("${t.javaClass.simpleName}: ${t.message}")
            }
            state = result
            loaded.countDown()
        }, "turn-models-load").start()
    }

    /** The loaded models, waiting up to [timeoutMs] for a load in progress; null if unusable. */
    fun awaitReady(context: Context, timeoutMs: Long): State.Ready? {
        if (broken != null) return null
        preload(context)
        runCatching { loaded.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return state as? State.Ready
    }
}
