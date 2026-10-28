package com.kevin.legion.service.turn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.random.Random

/**
 * On-device timing for the turn models (2026-10-27): Smart Turn feature extraction and inference on
 * 8 s of near-silence, [RUNS] times after [WARMUP] warm-up runs, plus Silero per 32 ms window. One
 * `TURN_BENCH` logcat line with median and p95 of each.
 *
 * Run it without touching the phone:
 * `adb shell am broadcast -a com.kevin.legion.TURN_BENCH -n com.kevin.legion/.service.turn.TurnBenchReceiver`
 *
 * The receiver is exported but guarded by `android.permission.DUMP`, which only the shell and the
 * system hold, so no app can make the phone burn CPU through it. It works on the `fast` build,
 * which has no debug rows.
 */
internal object TurnBench {
    const val TAG = "TURN_BENCH"
    private const val WARMUP = 3
    private const val RUNS = 20
    private const val SILERO_WINDOWS = 300

    /** [threads] other than the shipped [SmartTurnModel.INFERENCE_THREADS] loads a second session to compare. */
    fun run(context: Context, threads: Int = SmartTurnModel.INFERENCE_THREADS): String {
        val ready = TurnModels.awaitReady(context, 30_000L)
            ?: return "models unavailable: ${TurnModels.broken ?: TurnModels.state}".also { Log.i(TAG, it) }
        val model: TurnEndModel = if (threads == SmartTurnModel.INFERENCE_THREADS) {
            ready.smartTurn
        } else {
            SmartTurnModel(
                ai.onnxruntime.OrtEnvironment.getEnvironment(),
                context.assets.open(TurnModels.SMART_TURN_ASSET).use { it.readBytes() },
                threads,
            )
        }
        val rnd = Random(7)
        val audio = FloatArray(WhisperLogMel.N_SAMPLES) { (rnd.nextFloat() - 0.5f) * 0.002f }
        val mel = WhisperLogMel()
        val feat = LongArray(RUNS)
        val infer = LongArray(RUNS)
        var score = 0f
        repeat(WARMUP) { model.probability(mel.extract(audio)) }
        for (i in 0 until RUNS) {
            val t0 = System.nanoTime()
            val f = mel.extract(audio)
            val t1 = System.nanoTime()
            score = model.probability(f)
            val t2 = System.nanoTime()
            feat[i] = t1 - t0
            infer[i] = t2 - t1
        }
        val stream = ready.silero.stream()
        val window = FloatArray(SileroVad.WINDOW) { (rnd.nextFloat() - 0.5f) * 0.002f }
        val silero = LongArray(SILERO_WINDOWS)
        repeat(20) { stream.prob(window) }
        for (i in 0 until SILERO_WINDOWS) {
            val t0 = System.nanoTime()
            stream.prob(window)
            silero[i] = System.nanoTime() - t0
        }
        val total = LongArray(RUNS) { feat[it] + infer[it] }
        if (model !== ready.smartTurn) (model as? AutoCloseable)?.close()
        val line = "runs=$RUNS load_ms=${ready.loadMs} " +
            "feat_ms_p50=${ms(pct(feat, 50))} feat_ms_p95=${ms(pct(feat, 95))} " +
            "infer_ms_p50=${ms(pct(infer, 50))} infer_ms_p95=${ms(pct(infer, 95))} " +
            "total_ms_p50=${ms(pct(total, 50))} total_ms_p95=${ms(pct(total, 95))} " +
            "silero_us_p50=${pct(silero, 50) / 1000} silero_us_p95=${pct(silero, 95) / 1000} " +
            "threads=$threads score_on_silence=$score"
        Log.i(TAG, line)
        return line
    }

    private fun pct(values: LongArray, p: Int): Long {
        val s = values.sorted()
        val idx = ((p / 100.0) * (s.size - 1)).let { kotlin.math.ceil(it).toInt() }.coerceIn(0, s.size - 1)
        return s[idx]
    }

    private fun ms(nanos: Long): String = "%.1f".format(java.util.Locale.US, nanos / 1_000_000.0)
}

/** See [TurnBench]. Runs on its own thread so the broadcast returns at once. */
class TurnBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread({
            try {
                TurnBench.run(app, intent.getIntExtra("threads", SmartTurnModel.INFERENCE_THREADS))
            } catch (t: Throwable) {
                Log.w(TurnBench.TAG, "bench failed: ${t.message}", t)
            } finally {
                pending.finish()
            }
        }, "turn-bench").start()
    }
}
