package com.kevin.legion.service.speaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Enrolment and scoring maths on synthetic vectors and synthetic tones only. */
class SpeakerMathTest {
    private val tol = 1e-5f

    @Test
    fun `l2 normalise gives a unit vector`() {
        val u = SpeakerMath.l2Normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), u, tol)
    }

    @Test
    fun `cosine of same, opposite, orthogonal and zero vectors`() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, SpeakerMath.cosine(a, floatArrayOf(2f, 4f, 6f)), tol)
        assertEquals(-1f, SpeakerMath.cosine(a, floatArrayOf(-1f, -2f, -3f)), tol)
        assertEquals(0f, SpeakerMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), tol)
        assertEquals(0f, SpeakerMath.cosine(a, FloatArray(3)), tol)
        assertEquals(0f, SpeakerMath.cosine(a, floatArrayOf(1f, 2f)), tol)
    }

    @Test
    fun `the centroid is the mean of the normalised embeddings, not of the raw ones`() {
        // A loud and a quiet embedding pointing different ways weigh the same.
        val c = SpeakerMath.centroid(listOf(floatArrayOf(10f, 0f), floatArrayOf(0f, 0.1f)))!!
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), c, tol)
        assertEquals(1f, SpeakerMath.cosine(c, floatArrayOf(1f, 1f)), tol)
    }

    @Test
    fun `no centroid from nothing or from mixed sizes`() {
        assertNull(SpeakerMath.centroid(emptyList()))
        assertNull(SpeakerMath.centroid(listOf(floatArrayOf(1f), floatArrayOf(1f, 2f))))
    }

    @Test
    fun `percentile matches numpy's linear default`() {
        val v = doubleArrayOf(5.0, 1.0, 3.0, 2.0, 4.0)
        assertEquals(1.4, SpeakerMath.percentile(v, 10.0), 1e-9)
        assertEquals(3.0, SpeakerMath.percentile(v, 50.0), 1e-9)
        assertEquals(5.0, SpeakerMath.percentile(v, 100.0), 1e-9)
    }

    @Test
    fun `active rms ignores the quiet frames`() {
        // 25 frames of near-silence, 25 frames of a steady tone at amplitude 1000.
        val half = 25 * SpeakerMath.FRAME
        val pcm = ShortArray(2 * half) { i -> if (i < half) (i % 3 - 1).toShort() else tone(i, 1000) }
        val rms = SpeakerMath.activeRms(pcm)
        assertEquals(1000 / sqrt(2.0), rms, 15.0)
    }

    @Test
    fun `gain normalisation is level independent`() {
        val quiet = ShortArray(SpeakerMath.SAMPLE_RATE) { i -> if (i < 4000) 0 else tone(i, 300) }
        val loud = ShortArray(quiet.size) { (quiet[it] * 10).toShort() }
        val a = SpeakerMath.normalise(quiet)
        val b = SpeakerMath.normalise(loud)
        assertEquals(1f, SpeakerMath.cosine(a, b), 1e-3f)
        // Active frames land near the target level.
        val active = a.copyOfRange(8000, 16000)
        val r = sqrt(active.sumOf { it.toDouble() * it } / active.size)
        assertEquals(SpeakerMath.TARGET_ACTIVE_RMS, r, 0.005)
        assertTrue(a.all { it in -1f..1f })
    }

    @Test
    fun `the window is the loudest second ending at or before the hit`() {
        val sr = SpeakerMath.SAMPLE_RATE
        // 3 s: silence, a 1 s burst from 0.8 s to 1.8 s, silence. Hit at 2.0 s.
        val pcm = ShortArray(3 * sr) { i -> if (i in (sr * 8 / 10) until (sr * 18 / 10)) tone(i, 2000) else 0 }
        val w = SpeakerWindow.pick(pcm, end = 2 * sr)
        assertEquals(sr, w.size)
        assertTrue(w.count { it != 0.toShort() } > sr * 9 / 10)
        // Nothing after the hit is ever used: a burst after it is not picked.
        val late = ShortArray(3 * sr) { i -> if (i >= 2 * sr) tone(i, 2000) else 0 }
        assertTrue(SpeakerWindow.pick(late, end = 2 * sr).all { it == 0.toShort() })
    }

    @Test
    fun `short audio gives what there is, none gives nothing`() {
        val pcm = ShortArray(4000) { tone(it, 500) }
        assertEquals(4000, SpeakerWindow.pick(pcm).size)
        assertEquals(0, SpeakerWindow.pick(ShortArray(0)).size)
    }

    @Test
    fun `the ring keeps the newest samples in order`() {
        val ring = SpeakerRing(5)
        ring.push(shortArrayOf(1, 2, 3), 3)
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.snapshot())
        ring.push(shortArrayOf(4, 5, 6, 7, 99), 4)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), ring.snapshot())
        ring.reset()
        assertEquals(0, ring.snapshot().size)
    }

    private fun tone(i: Int, amp: Int): Short =
        (amp * kotlin.math.sin(2 * Math.PI * 220 * i / SpeakerMath.SAMPLE_RATE)).toInt().toShort()
}
