package com.kevin.legion.service.turn

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The models themselves, run through the same Kotlin wrappers the phone uses, on the JVM build of
 * ONNX Runtime: Smart Turn on KOTLIN features must give the probability Python got on Python features,
 * and Silero must give Python's per-window probabilities.
 */
class TurnModelsGoldenTest {
    private val env = OrtEnvironment.getEnvironment()
    private fun asset(name: String): ByteArray {
        val candidates = listOf(File("src/main/assets/turn/$name"), File("app/src/main/assets/turn/$name"))
        return candidates.first { it.exists() }.readBytes()
    }

    @Test
    fun `smart turn on kotlin features matches python`() {
        SmartTurnModel(env, asset("smart-turn-v3.2-cpu.onnx"), threads = 1).use { model ->
            for (name in listOf("short", "long")) {
                val expected = TurnGolden.meta.getJSONObject("vectors").getJSONObject(name).getDouble("smart_turn_prob")
                val got = model.probability(WhisperLogMel().extract(TurnGolden.audio(name)))
                assertEquals(name, expected, got.toDouble(), 0.01)
            }
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
