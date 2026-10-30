package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Ticket 20: the WAV writer, the sample capture and the enrollment script, as pure units. */
class WakeSamplesTest {
    private val rate = WakeSampleCapture.SAMPLE_RATE
    private val chunk = rate / 5 // the engine's 200 ms read

    private fun chunkOf(value: Short) = ShortArray(chunk) { value }

    @Test
    fun `wav header describes 16 kHz mono 16-bit pcm`() {
        val pcm = shortArrayOf(1, -2, 3)
        val bytes = WavEncoder.encode(pcm, rate)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + 6, b.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4))
        assertEquals(1, b.getShort(20).toInt())
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(rate, b.getInt(24))
        assertEquals(rate * 2, b.getInt(28))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(6, b.getInt(40))
        assertEquals(-2, b.getShort(46).toInt())
        assertEquals(44 + 6, bytes.size)
    }

    @Test
    fun `nothing is recorded while live saving is off and no take is open`() {
        val c = WakeSampleCapture()
        repeat(10) { assertTrue(c.onAudio(chunkOf(5), chunk).isEmpty()) }
        assertTrue(c.onSignal(WakeSignal.VadOn).isEmpty())
        repeat(3) { c.onAudio(chunkOf(5), chunk) }
        assertTrue(c.onSignal(WakeSignal.VadOff).isEmpty())
        assertTrue(c.flush().isEmpty())
    }

    @Test
    fun `a live segment carries one second of pre-roll and the verdicts`() {
        val c = WakeSampleCapture().apply { liveOn = true }
        repeat(10) { c.onAudio(chunkOf(1), chunk) } // 2 s of lead-in
        c.onSignal(WakeSignal.VadOn)
        repeat(4) { c.onAudio(chunkOf(9), chunk) }
        c.onSignal(WakeSignal.KwsHit("hey_alfred", listOf("▁HE", "Y"), listOf(0.1f, 0.2f)))
        c.onSignal(WakeSignal.Confirm("hey", "{\"text\":\"hey\"}", accepted = false))
        val out = c.onSignal(WakeSignal.VadOff)
        assertEquals(1, out.size)
        val s = out.single()
        assertEquals("live", s.kind)
        assertEquals(rate + 4 * chunk, s.pcm.size)
        assertEquals(1, s.pcm.first().toInt())
        assertEquals(9, s.pcm.last().toInt())
        assertEquals("confirm_rejected", s.sidecar.getString("verdict"))
        assertEquals("hey", s.sidecar.getString("confirmText"))
        assertTrue(s.sidecar.getBoolean("kwsHit"))
        val events = s.sidecar.getJSONArray("events")
        assertEquals("vad_on", events.getJSONObject(0).getString("stage"))
        assertEquals(rate.toLong(), events.getJSONObject(0).getLong("atSample"))
        assertEquals("vad_off", events.getJSONObject(events.length() - 1).getString("stage"))
    }

    @Test
    fun `a take is continuous audio of its window whatever the vad does`() {
        val c = WakeSampleCapture()
        c.onAudio(chunkOf(1), chunk)
        c.beginTake("pos_normal_01", maxMs = 1_000L, meta = mapOf("source" to "VOICE_COMMUNICATION"))
        val out = mutableListOf<WakeSampleCapture.Sample>()
        repeat(2) { out += c.onAudio(chunkOf(7), chunk) }
        out += c.onSignal(WakeSignal.VadOn)
        repeat(4) { out += c.onAudio(chunkOf(7), chunk) }
        assertEquals(1, out.size)
        val s = out.single()
        assertEquals("take", s.kind)
        assertEquals("pos_normal_01", s.label)
        assertEquals(rate, s.pcm.size)
        assertTrue(s.pcm.all { it.toInt() == 7 })
        assertEquals("max_length", s.sidecar.getString("closedBy"))
        assertEquals("kws_miss", s.sidecar.getString("verdict"))
        assertEquals("VOICE_COMMUNICATION", s.sidecar.getString("source"))
        assertFalse(c.takeOpen)
    }

    @Test
    fun `ending or flushing writes what was captured`() {
        val c = WakeSampleCapture().apply { liveOn = true }
        c.beginTake("neg_ambient_01", maxMs = 120_000L, meta = emptyMap())
        c.onSignal(WakeSignal.VadOn)
        c.onAudio(chunkOf(3), chunk)
        c.onSignal(WakeSignal.Suppressed)
        val flushed = c.flush()
        assertEquals(listOf("live", "take"), flushed.map { it.kind })
        assertEquals("capture_released", flushed.first().sidecar.getString("closedBy"))
        assertEquals(chunk, flushed.last().pcm.size)
    }

    @Test
    fun `verdict words`() {
        assertEquals("opened", WakeSampleCapture.verdict(kwsHit = true, accepted = true))
        assertEquals("confirm_rejected", WakeSampleCapture.verdict(kwsHit = true, accepted = false))
        assertEquals("kws_hit", WakeSampleCapture.verdict(kwsHit = true, accepted = null))
        assertEquals("kws_miss", WakeSampleCapture.verdict(kwsHit = false, accepted = null))
    }

    @Test
    fun `the enrollment script is twenty positives then one negative`() {
        val steps = WakeEnrollPlan.steps("hey alfred")
        assertEquals(20, WakeEnrollPlan.POSITIVE_COUNT)
        assertEquals(21, steps.size)
        assertEquals(20, steps.count { it.positive })
        assertEquals(steps.size, steps.map { it.label }.toSet().size)
        assertTrue(steps.filter { it.positive }.all { it.label.matches(Regex("pos_[a-z]+_\\d\\d")) })
        assertTrue(steps.filter { it.positive }.all { it.instruction.contains("\"hey alfred\"") })
        val conditions = steps.filter { it.positive }.map { it.label.split("_")[1] }.toSet()
        assertEquals(setOf("normal", "quiet", "far", "fast", "music"), conditions)
        assertEquals("neg_ambient_01", steps.last().label)
        assertEquals(WakeEnrollPlan.NEGATIVE_WINDOW_MS, steps.last().windowMs)
    }
}
