package com.kevin.legion.service.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The Kotlin port against Hugging Face's WhisperFeatureExtractor, on vectors from
 * `tools/turn/make_turn_golden.py`. "short" (3.2 s) exercises the front zero-padding, "long" (9 s)
 * the 8 s cut and the reflect padding at the window start.
 */
class WhisperLogMelGoldenTest {
    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
        assertEquals(a.size, b.size)
        var m = 0f
        for (i in a.indices) m = maxOf(m, abs(a[i] - b[i]))
        return m
    }

    @Test
    fun `features match the Python extractor`() {
        for (name in listOf("short", "long")) {
            val got = WhisperLogMel().extract(TurnGolden.audio(name))
            val diff = maxAbsDiff(got, TurnGolden.features(name))
            assertTrue("$name: max abs diff $diff", diff < TOLERANCE)
        }
    }

    @Test
    fun `turn path matches the Python extractor at a hop-aligned length`() {
        for (name in listOf("short", "long")) {
            val audio = TurnGolden.audio(name)
            val got = WhisperLogMel().extractTurn(audio, audio.size, Any())
            assertTrue(maxAbsDiff(got, TurnGolden.features(name)) < TOLERANCE)
        }
    }

    @Test
    fun `a re-check later in the turn reuses frames and matches a fresh extraction`() {
        val audio = TurnGolden.audio("long")
        val mel = WhisperLogMel()
        val key = Any()
        mel.extractTurn(audio, 120_000, key)
        // 120,000 samples leave 8,000 of front padding; frames wholly inside it are not transformed.
        assertEquals(WhisperLogMel.N_FRAMES - 47, mel.lastFramesComputed) // frames 2..48
        val second = mel.extractTurn(audio, 128_000 + 9_600, key).copyOf()
        assertTrue("recomputed ${mel.lastFramesComputed}", mel.lastFramesComputed < 120)
        val fresh = WhisperLogMel().extract(audio.copyOf(128_000 + 9_600))
        assertTrue(maxAbsDiff(second, fresh) < 1e-4f)
    }

    @Test
    fun `a new turn key drops the cache`() {
        val audio = TurnGolden.audio("short")
        val mel = WhisperLogMel()
        mel.extractTurn(audio, audio.size, Any())
        val first = mel.lastFramesComputed
        mel.extractTurn(audio, audio.size, Any())
        assertEquals(first, mel.lastFramesComputed)
        mel.extractTurn(audio, audio.size, Any().also { mel.resetTurn() })
        assertEquals(first, mel.lastFramesComputed)
    }

    @Test
    fun `real FFT matches a plain DFT`() {
        val x = FloatArray(400) { ((it * 37) % 101 - 50) / 50f }
        val re = FloatArray(201)
        val im = FloatArray(201)
        RealFft400().transform(x, re, im)
        for (k in 0..200) {
            var r = 0.0
            var i = 0.0
            for (n in 0 until 400) {
                val a = -2.0 * Math.PI * k * n / 400
                r += x[n] * kotlin.math.cos(a)
                i += x[n] * kotlin.math.sin(a)
            }
            assertEquals(r, re[k].toDouble(), 1e-2)
            assertEquals(i, im[k].toDouble(), 1e-2)
        }
    }

    private companion object {
        // Features live around -1.5..1.5; float32 against numpy's float64/complex64 path.
        const val TOLERANCE = 2e-3f
    }
}
