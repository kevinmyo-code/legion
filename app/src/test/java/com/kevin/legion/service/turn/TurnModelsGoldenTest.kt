package com.kevin.legion.service.turn

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer

/**
 * The models themselves, run through the same Kotlin wrappers the phone uses, on the JVM build of
 * ONNX Runtime. Silero must give Python's per-window probabilities.
 *
 * **Do not re-add a tight cross-machine probability assert for Smart Turn.** It is an int8 quantized
 * (QDQ) model, and its probability can move by about 0.1 on rounding-level input differences.
 * Measured 2026-11-20 with Python ORT 1.23 on the stored "long" features: Gaussian noise of 1e-7 added
 * to the features (8 draws) spread the output 0.46 to 0.57; the same input gave 0.46 to 0.65 across ORT
 * optimisation levels. In CI on 679f7c15, "long" (Python 0.4803) came out 0.5877 on one runner and
 * passed on the next, against the 0.01 tolerance this test used to have.
 *
 * So Smart Turn is held four ways instead: the model files' SHA-256; the wrapper against a raw session
 * on the same machine, exactly (input name, shape, layout, output read); the done / not-done DECISION
 * against Python on two inputs far from the 0.5 threshold (far_high, far_low), also at the phone's
 * thread count; and only a +/-0.2 sanity band on the two near the threshold (short, long). Kotlin
 * features are held to Python's by [WhisperLogMelGoldenTest].
 */
class TurnModelsGoldenTest {
    private val env = OrtEnvironment.getEnvironment()
    private fun asset(name: String): ByteArray {
        val candidates = listOf(File("src/main/assets/turn/$name"), File("app/src/main/assets/turn/$name"))
        return candidates.first { it.exists() }.readBytes()
    }

    /** The model as its card documents it: `input_features` [1, 80, 800], one probability out. */
    private fun rawSmartTurn(session: OrtSession, features: FloatArray): Float =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, 80, 800)).use { t ->
            session.run(mapOf("input_features" to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                (r.get(0).value as Array<FloatArray>)[0][0]
            }
        }

    private fun pythonProb(name: String): Double =
        TurnGolden.meta.getJSONObject("vectors").getJSONObject(name).getDouble("smart_turn_prob")

    private fun rawSession(bytes: ByteArray): OrtSession = OrtSession.SessionOptions().use { opts ->
        // The wrapper's own options: the same kernels, so the same features give the same bits.
        opts.setIntraOpNumThreads(1)
        opts.setInterOpNumThreads(1)
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(bytes, opts)
    }

    // The model files the goldens were made with (assets/turn/README.md): a swapped or re-exported
    // file fails here, before any probability is compared.
    @Test
    fun `the bundled model files are the ones the goldens were made with`() {
        fun sha256(name: String) = java.security.MessageDigest.getInstance("SHA-256").digest(asset(name))
            .joinToString("") { "%02x".format(it) }
        val smartTurn = "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f"
        val silero = "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3"
        assertEquals(smartTurn, sha256("smart-turn-v3.2-cpu.onnx"))
        assertEquals(silero, sha256("silero_vad_v6_2.onnx"))
    }

    @Test
    fun `smart turn wrapper gives the raw model's output, on python's and on kotlin's features`() {
        val bytes = asset("smart-turn-v3.2-cpu.onnx")
        rawSession(bytes).use { raw ->
            SmartTurnModel(env, bytes, threads = 1).use { model ->
                for (name in listOf("short", "long")) {
                    val python = TurnGolden.features(name)
                    assertEquals("$name, python features", rawSmartTurn(raw, python), model.probability(python), 0f)
                    val kotlin = WhisperLogMel().extract(TurnGolden.audio(name))
                    assertEquals("$name, kotlin features", rawSmartTurn(raw, kotlin), model.probability(kotlin), 0f)
                }
            }
        }
    }

    // Cross-implementation, at the level the product uses: Python's reference far from the 0.5
    // threshold (far_high about 0.96, far_low about 0.01, both robust to feature noise up to 2e-3 in
    // tools/turn/make_turn_golden.py's own runs), and the app's wrapper on KOTLIN features must make
    // the same done / not-done call. Only the decision is asserted, never the probability.
    @Test
    fun `smart turn on kotlin features makes python's decision on inputs far from the threshold`() {
        SmartTurnModel(env, asset("smart-turn-v3.2-cpu.onnx"), threads = 1).use { model ->
            for (name in listOf("far_high", "far_low")) {
                val python = pythonProb(name)
                val kotlin = model.probability(WhisperLogMel().extract(TurnGolden.audio(name)))
                assertEquals(name, python >= EndOfTurnPolicy.THRESHOLD, kotlin >= EndOfTurnPolicy.THRESHOLD)
            }
        }
    }

    // The phone runs Smart Turn on SmartTurnModel.INFERENCE_THREADS (4); the tests above run 1 thread.
    // The decision on the far inputs must not depend on the thread count. The probability may.
    @Test
    fun `the decision is the same at one thread and at the phone's thread count`() {
        val bytes = asset("smart-turn-v3.2-cpu.onnx")
        SmartTurnModel(env, bytes, threads = 1).use { one ->
            SmartTurnModel(env, bytes, threads = SmartTurnModel.INFERENCE_THREADS).use { phone ->
                for (name in listOf("far_high", "far_low")) {
                    val features = WhisperLogMel().extract(TurnGolden.audio(name))
                    val single = one.probability(features) >= EndOfTurnPolicy.THRESHOLD
                    assertEquals(name, single, phone.probability(features) >= EndOfTurnPolicy.THRESHOLD)
                    assertEquals(name, name == "far_high", single)
                }
            }
        }
    }

    // A SANITY band only, not a golden: "short" (0.67) and "long" (0.48) sit near the threshold, where
    // this quantized model's output moves by about 0.1 between machines. +/-0.2 catches a broken
    // model or wiring (an output pinned at 0 or 1, or a different model), nothing finer.
    @Test
    fun `smart turn near the threshold stays within a wide band of python's value`() {
        SmartTurnModel(env, asset("smart-turn-v3.2-cpu.onnx"), threads = 1).use { model ->
            for (name in listOf("short", "long")) {
                val python = pythonProb(name)
                val kotlin = model.probability(WhisperLogMel().extract(TurnGolden.audio(name)))
                assertEquals(name, python, kotlin.toDouble(), SANITY_BAND)
            }
        }
    }

    @Test
    fun `the wrapper check can fail - frames in the wrong order give another output`() {
        val bytes = asset("smart-turn-v3.2-cpu.onnx")
        rawSession(bytes).use { raw ->
            val f = TurnGolden.features("long")
            // [80, 800] read as [800, 80]: what a swapped shape in the wrapper would feed the model.
            val swapped = FloatArray(f.size) { i -> f[(i % 80) * 800 + i / 80] }
            assertNotEquals(rawSmartTurn(raw, f), rawSmartTurn(raw, swapped), 1e-4f)
        }
    }

    @Test
    fun `silero stream matches python per window`() {
        SileroVad(env, asset("silero_vad_v6_2.onnx")).use { vad ->
            val stream = vad.stream()
            val audio = TurnGolden.audio("short")
            val expected = TurnGolden.meta.getJSONArray("silero_short")
            for (i in 0 until expected.length()) {
                val p = stream.prob(audio.copyOfRange(i * 512, (i + 1) * 512))
                assertEquals("window $i", expected.getDouble(i), p.toDouble(), 1e-3)
            }
        }
    }

    private companion object {
        const val SANITY_BAND = 0.2
    }
}
