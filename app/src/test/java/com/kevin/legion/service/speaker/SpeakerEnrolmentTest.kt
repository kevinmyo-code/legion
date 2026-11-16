package com.kevin.legion.service.speaker

import com.kevin.legion.service.WakeSampleCapture
import com.kevin.legion.service.WakeSignal
import com.kevin.legion.service.WavEncoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which takes build the voiceprint, where their window sits, the voiceprint file and the WAV
 * reader. Takes are made by the real [WakeSampleCapture] from synthetic tones, so the sidecar
 * format is the one the phone writes.
 */
class SpeakerEnrolmentTest {
    private val sr = SpeakerMath.SAMPLE_RATE
    private val oww = mapOf(WakeSampleCapture.META_ENGINE to WakeSampleCapture.ENGINE_OWW)

    /** A 3 s take: [hitAtChunk] chunks of 200 ms in, the engine says it would have opened. */
    private fun take(
        label: String,
        signal: WakeSignal? = WakeSignal.Suppressed,
        hitAtChunk: Int = 10,
    ): WakeSampleCapture.Sample {
        val cap = WakeSampleCapture()
        cap.beginTake(label, maxMs = 3_000, meta = oww)
        val chunk = ShortArray(sr / 5)
        var out: WakeSampleCapture.Sample? = null
        for (c in 0 until 15) {
            for (i in chunk.indices) chunk[i] = ((c * 37 + i) % 200 - 100).toShort()
            out = cap.onAudio(chunk, chunk.size).firstOrNull() ?: out
            if (c == hitAtChunk - 1 && signal != null) cap.onSignal(signal, oww)
        }
        return out ?: cap.endTake()!!
    }

    @Test
    fun `a prompted take the engine confirmed counts`() {
        val t = take("pos_normal_01")
        assertTrue(SpeakerEnrolment.eligible(t.sidecar))
        assertEquals(10 * sr / 5, SpeakerEnrolment.hitSample(t.sidecar))
    }

    @Test
    fun `the ambient minute never counts, nor a take with no confirmed hit`() {
        assertFalse(SpeakerEnrolment.eligible(take("neg_ambient_01").sidecar))
        assertFalse(SpeakerEnrolment.eligible(take("pos_quiet_02", signal = null).sidecar))
        // A spotter hit alone is not a confirmed wake.
        val kwsOnly = take("pos_far_01", signal = WakeSignal.KwsHit("hey", listOf("hey"), listOf(0.1f)))
        assertFalse(SpeakerEnrolment.eligible(kwsOnly.sidecar))
    }

    @Test
    fun `the event decides, not a stale verdict string`() {
        val side = take("pos_fast_01").sidecar.put("verdict", "kws_miss")
        assertTrue(SpeakerEnrolment.eligible(side))
    }

    @Test
    fun `a take's window ends at its hit`() {
        val t = take("pos_normal_02")
        val hit = SpeakerEnrolment.hitSample(t.sidecar)!!
        val w = SpeakerEnrolment.window(t.sidecar, t.pcm)!!
        assertEquals(sr, w.size)
        assertArrayEquals(SpeakerWindow.pick(t.pcm, hit), w)
    }

    @Test
    fun `too few takes builds nothing`() {
        val windows = List(SpeakerPolicy.MIN_ENROL_TAKES - 1) { ShortArray(sr) }
        val built = SpeakerEnrolment.build(windows) { floatArrayOf(1f, 0f) }
        assertEquals(SpeakerEnrolment.Built.TooFew(SpeakerPolicy.MIN_ENROL_TAKES - 1), built)
    }

    @Test
    fun `enough takes build the mean of the normalised embeddings`() {
        val n = SpeakerPolicy.MIN_ENROL_TAKES
        val windows = List(n) { i -> ShortArray(sr) { i.toShort() } }
        // Half point one way, half the other, at different lengths.
        val built = SpeakerEnrolment.build(windows) { w ->
            if (w[0] % 2 == 0) floatArrayOf(5f, 0f) else floatArrayOf(0f, 0.2f)
        } as SpeakerEnrolment.Built.Ready
        assertEquals(n, built.takes)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), built.voiceprint, 1e-5f)
    }

    @Test
    fun `a held wake is recorded as a hit with its decision and no score`() {
        val t = take("live", signal = WakeSignal.SpeakerHeld("ignore"))
        assertEquals("oww_hit", t.sidecar.getString("verdict"))
        val events: JSONArray = t.sidecar.getJSONArray("events")
        val e: JSONObject = events.getJSONObject(0)
        assertEquals("speaker_held", e.getString("stage"))
        assertEquals("ignore", e.getString("decision"))
        assertFalse(e.has("score"))
    }

    @Test
    fun `the voiceprint file round-trips and refuses anything else`() {
        val v = VoiceprintCodec.Voiceprint(floatArrayOf(0.1f, -0.2f, 0.3f), takes = 19, builtAtMs = 1_700_000_000_000L)
        val back = VoiceprintCodec.decode(VoiceprintCodec.encode(v))
        assertNotNull(back)
        assertArrayEquals(v.vector, back!!.vector, 0f)
        assertEquals(19, back.takes)
        assertEquals(1_700_000_000_000L, back.builtAtMs)
        val bytes = VoiceprintCodec.encode(v)
        assertNull(VoiceprintCodec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(VoiceprintCodec.decode(ByteArray(0)))
        assertNull(VoiceprintCodec.decode(bytes.copyOf().also { it[0] = 0 }))
    }

    @Test
    fun `the wav reader reads what the recorder writes and nothing else`() {
        val pcm = ShortArray(1234) { (it * 7 - 3000).toShort() }
        assertArrayEquals(pcm, WavReader.pcm16(WavEncoder.encode(pcm, sr)))
        assertNull(WavReader.pcm16(WavEncoder.encode(pcm, 8_000)))
        assertNull(WavReader.pcm16(ByteArray(10)))
    }
}
