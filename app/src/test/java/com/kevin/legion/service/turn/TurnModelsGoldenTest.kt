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
 * ONNX Runtime. Silero must give Python's per-window probabilities. Smart Turn is held differently
 * (2026-11-20, CI flake on 679f7c15: "long" expected 0.4803, got 0.5877 on one runner, passed on the
 * next): it is an int8 QDQ model, and on the golden inputs its output moves by up to 0.1 for feature
 * noise of 1e-7 (float32 rounding level) and differs by ORT version and CPU instruction set, so "Kotlin
 * features give Python's probability within 0.01" was never a stable claim. What is held instead,
 * each exactly on one machine: the Kotlin features match Python's ([WhisperLogMelGoldenTest]), and the
 * wrapper gives the raw model's output for the same features, so its input name, shape, layout and
 * output read are right.
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

    private fun rawSession(bytes: ByteArray): OrtSession = OrtSession.SessionOptions().use { opts ->
        // The wrapper's own options: the same kernels, so the same features give the same bits.
        opts.setIntraOpNumThreads(1)
        opts.setInterOpNumThreads(1)
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        env.createSession(bytes, opts)
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
}
