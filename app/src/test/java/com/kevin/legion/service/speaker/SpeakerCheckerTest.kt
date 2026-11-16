package com.kevin.legion.service.speaker

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The check around the model: no voiceprint, no model, a throw, the deadline, one at a time. The
 * "model" is a fake that returns fixed synthetic vectors; no audio here is anyone's voice.
 */
class SpeakerCheckerTest {
    private val pool = Executors.newSingleThreadExecutor()
    private val window = ShortArray(SpeakerMath.SAMPLE_RATE) { (it % 50 * 20).toShort() }
    private val print = floatArrayOf(1f, 0f, 0f)

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun fixed(v: FloatArray) = object : SpeakerEmbedder {
        override fun embed(samples: FloatArray) = v
    }

    private fun checker(state: EmbedderState, vp: FloatArray? = print) =
        SpeakerChecker(pool, { state }, { vp })

    @Test
    fun `a matching embedding scores 1, an orthogonal one 0`() {
        val same = checker(EmbedderState.Ready(fixed(floatArrayOf(2f, 0f, 0f)))).gated(window)
        assertEquals(1f, (same as SpeakerResult.Scored).score, 1e-5f)
        val other = checker(EmbedderState.Ready(fixed(floatArrayOf(0f, 3f, 0f)))).gated(window)
        assertEquals(0f, (other as SpeakerResult.Scored).score, 1e-5f)
    }

    @Test
    fun `no voiceprint says so and never runs the model`() {
        var ran = false
        val e = object : SpeakerEmbedder {
            override fun embed(samples: FloatArray): FloatArray {
                ran = true
                return print
            }
        }
        assertEquals(SpeakerResult.NoVoiceprint, checker(EmbedderState.Ready(e), vp = null).gated(window))
        val shadowed = AtomicReference<SpeakerResult>()
        checker(EmbedderState.Ready(e), vp = null).shadow(window) { shadowed.set(it) }
        assertEquals(SpeakerResult.NoVoiceprint, shadowed.get())
        assertTrue(!ran)
    }

    @Test
    fun `a missing or loading model says which`() {
        assertEquals(SpeakerResult.ModelMissing, checker(EmbedderState.Missing).gated(window))
        assertEquals(SpeakerResult.ModelLoading, checker(EmbedderState.Loading).gated(window))
    }

    @Test
    fun `no audio is its own result`() {
        assertEquals(SpeakerResult.NoAudio, checker(EmbedderState.Ready(fixed(print))).gated(ShortArray(0)))
    }

    @Test
    fun `a model that throws is a failure by class name`() {
        val boom = object : SpeakerEmbedder {
            override fun embed(samples: FloatArray): FloatArray = error("native said no")
        }
        assertEquals(SpeakerResult.Failed("IllegalStateException"), checker(EmbedderState.Ready(boom)).gated(window))
    }

    @Test
    fun `past the deadline it times out, and the next hit is busy until the slow one ends`() {
        val release = CountDownLatch(1)
        val slow = object : SpeakerEmbedder {
            override fun embed(samples: FloatArray): FloatArray {
                release.await(5, TimeUnit.SECONDS)
                return print
            }
        }
        val c = checker(EmbedderState.Ready(slow))
        assertEquals(SpeakerResult.TimedOut(50), c.gated(window, deadlineMs = 50))
        assertEquals(SpeakerResult.Busy, c.gated(window, deadlineMs = 50))
        release.countDown()
        // The slow check finishes on its thread; after it, checks run again.
        pool.submit(Runnable {}).get(5, TimeUnit.SECONDS)
        assertTrue(c.gated(window, deadlineMs = 1_000) is SpeakerResult.Scored)
    }

    @Test
    fun `shadow does not wait and reports the score from the worker`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : SpeakerEmbedder {
            override fun embed(samples: FloatArray): FloatArray {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                return print
            }
        }
        val got = AtomicReference<SpeakerResult>()
        val done = CountDownLatch(1)
        checker(EmbedderState.Ready(slow)).shadow(window) {
            got.set(it)
            done.countDown()
        }
        // Returned while the model is still running.
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(null, got.get())
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1f, (got.get() as SpeakerResult.Scored).score, 1e-5f)
    }

    @Test
    fun `the check times itself`() {
        var t = 1_000L
        val c = SpeakerChecker(pool, { EmbedderState.Ready(fixed(print)) }, { print }, nowMs = { t.also { t += 42 } })
        assertEquals(42L, (c.gated(window) as SpeakerResult.Scored).ms)
    }
}
