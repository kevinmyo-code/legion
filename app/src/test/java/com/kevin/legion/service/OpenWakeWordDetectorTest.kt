package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The detector around fake models: what it logs per decision, and the wake gate's rule that no model
 * is freed while a chunk is running (the 2026-11-10 crash class), with the same gate the engine uses.
 */
class OpenWakeWordDetectorTest {

    /** Scores [scores] in order, one per frame after the five start-up frames; blocks on [hold] if set. */
    private class FakeModels(
        private val scores: List<Float>,
        private val hold: Pair<CountDownLatch, CountDownLatch>? = null,
    ) : OwwModels {
        private var next = 0
        val closed = AtomicBoolean(false)
        val inside = AtomicBoolean(false)
        override val inputFrames = 16
        override fun mel(samples: FloatArray) = Array((samples.size - 512) / 160 + 1) { FloatArray(32) }
        override fun embed(window: Array<FloatArray>) = FloatArray(96)
        override fun score(features: Array<FloatArray>): Float {
            check(!closed.get()) { "scored after close" }
            hold?.let { (entered, release) ->
                inside.set(true)
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                inside.set(false)
            }
            return scores.getOrElse(next++) { 0f }
        }
        override fun close() {
            closed.set(true)
        }
    }

    private fun frames(n: Int) = ShortArray(1280 * n)

    @Test
    fun `an open is logged with engine, score and threshold, and returns true`() {
        val log = mutableListOf<String>()
        val warmup = List(5) { 0f }
        val det = OpenWakeWordDetector(FakeModels(warmup + listOf(0.2f, 0.81f, 0.9f, 0.1f)), 0.5f) { log += it }
        assertFalse(det.process(frames(5), 1280 * 5, nowMs = 1_000))
        assertTrue(det.process(frames(4), 1280 * 4, nowMs = 2_000))
        assertEquals(listOf("decision engine=openwakeword verdict=open score=0.810 threshold=0.50 gain=1"), log)
    }

    @Test
    fun `a near miss is logged as below, with its peak`() {
        val log = mutableListOf<String>()
        val det = OpenWakeWordDetector(FakeModels(List(5) { 0f } + listOf(0.3f, 0.42f, 0.1f)), 0.5f) { log += it }
        assertFalse(det.process(frames(8), 1280 * 8, nowMs = 1_000))
        assertEquals(listOf("decision engine=openwakeword verdict=below score=0.420 threshold=0.50 gain=1"), log)
    }

    @Test
    fun `the keyword spotter's decision line has the same shape`() {
        val line = WakeTwoStageDetector.decisionLine("open")
        assertTrue(line, line.startsWith("decision engine=kws verdict=open score=- threshold=0.10 gain="))
    }

    @Test
    fun `release after the gate closes waits for a running chunk, and nothing scores after`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val models = FakeModels(List(20) { 0.1f }, hold = entered to release)
        val det = OpenWakeWordDetector(models, 0.5f) {}
        val gate = WakeCaptureGate()
        val ticket = gate.open()
        val freedUnderChunk = AtomicBoolean(false)

        val capture = Thread { gate.whileOpen(ticket) { det.process(frames(1), 1280) } }
        capture.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val done = CountDownLatch(1)
        // The engine's stop(): close the gate (waits for the chunk), then free the models.
        val teardown = Thread {
            gate.close()
            if (models.inside.get()) freedUnderChunk.set(true)
            det.release()
            done.countDown()
        }
        teardown.start()
        assertFalse("teardown must wait for the chunk", done.await(200, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        capture.join(5_000)
        teardown.join(5_000)
        assertFalse(freedUnderChunk.get())
        assertTrue(models.closed.get())
        assertFalse("a closed capture runs no chunk", gate.whileOpen(ticket) { det.process(frames(1), 1280) })
    }

    @Test
    fun `the head file is named after the wake phrase`() {
        assertEquals("hey_alfred.onnx", OwwModelFiles.headName("Alfred"))
    }
}
