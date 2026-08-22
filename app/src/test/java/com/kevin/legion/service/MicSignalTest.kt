package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ticket 15 (`.scratch/wake-word/issues/15-see-a-deaf-mic.md`): the two tests named in the
 * ticket's own verification section are `peakAmplitude distinguishes silence, room noise, and
 * speech` and `the watchdog does not fire on a quiet working mic but does fire on a digitally
 * silent one`, below. Everything else here is scaffolding for those two.
 *
 * No `AudioRecord`, no `Context`, nothing Android - [MicSignal] takes plain `ByteArray`s, so these
 * run as ordinary JVM unit tests with no Robolectric shadow required.
 */
class MicSignalTest {

    /** Builds `sampleCount` little-endian PCM16 samples, all at the same fixed amplitude. */
    private fun constantPcm16(amplitude: Short, sampleCount: Int): ByteArray {
        val buf = ByteArray(sampleCount * 2)
        var i = 0
        repeat(sampleCount) {
            val v = amplitude.toInt()
            buf[i] = (v and 0xFF).toByte()
            buf[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        return buf
    }

    /**
     * A sine wave at the given peak amplitude - closer to a real captured signal than a flat
     * constant, and exercises the negative-sample branch of [MicSignal.peakAmplitude] (a flat
     * positive constant never does).
     */
    private fun sinePcm16(peakAmplitude: Int, sampleCount: Int): ByteArray {
        val buf = ByteArray(sampleCount * 2)
        var i = 0
        for (n in 0 until sampleCount) {
            val v = (peakAmplitude * sin(2.0 * Math.PI * 7.0 * n / sampleCount)).roundToInt()
            buf[i] = (v and 0xFF).toByte()
            buf[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
        return buf
    }

    // ---------------------------------------------------------------- peakAmplitude

    @Test
    fun `peakAmplitude reads digital silence as zero`() {
        val buf = constantPcm16(0, sampleCount = 800)
        assertEquals(0, MicSignal.peakAmplitude(buf, buf.size))
    }

    @Test
    fun `peakAmplitude finds a positive peak in a room-noise-level signal`() {
        // Room noise: small, non-zero. Chosen well under SILENCE_PEAK_THRESHOLD so this
        // doubles as the "working mic in a quiet room" fixture used by the watchdog test below.
        val buf = sinePcm16(peakAmplitude = 250, sampleCount = 1600)
        val peak = MicSignal.peakAmplitude(buf, buf.size)
        assertTrue("expected a small positive peak, got $peak", peak in 200..260)
    }

    @Test
    fun `peakAmplitude finds a large peak in a speech-level signal`() {
        // Speech: loud relative to room noise. Comfortably above the watchdog's threshold.
        val buf = sinePcm16(peakAmplitude = 12000, sampleCount = 1600)
        val peak = MicSignal.peakAmplitude(buf, buf.size)
        assertTrue("expected a large peak, got $peak", peak in 11500..12000)
    }

    @Test
    fun `peakAmplitude distinguishes silence, room noise, and speech`() {
        val silence = MicSignal.peakAmplitude(constantPcm16(0, 800), 1600)
        val roomNoise = MicSignal.peakAmplitude(sinePcm16(250, 1600), 3200)
        val speech = MicSignal.peakAmplitude(sinePcm16(12000, 1600), 3200)

        // The whole discriminator the ticket asks for: these three are ordered and clearly
        // separated, not just "different by one".
        assertTrue("silence ($silence) should be near zero", silence <= 5)
        assertTrue("room noise ($roomNoise) should sit above silence", roomNoise > silence)
        assertTrue("speech ($speech) should sit well above room noise", speech > roomNoise * 10)
    }

    @Test
    fun `peakAmplitude reads correctly-signed negative samples`() {
        // A single sample at Short.MIN_VALUE (-32768). If the sign bit were mishandled (e.g.
        // treating the high byte as unsigned), this would read as a small positive number
        // instead of the largest possible magnitude.
        val buf = constantPcm16(Short.MIN_VALUE, sampleCount = 4)
        assertEquals(32768, MicSignal.peakAmplitude(buf, buf.size))
    }

    @Test
    fun `peakAmplitude ignores a trailing odd byte rather than throwing`() {
        val buf = constantPcm16(1000, sampleCount = 4) + byteArrayOf(0x7F)
        // Should not throw, and the trailing lone byte contributes nothing.
        assertEquals(1000, MicSignal.peakAmplitude(buf, buf.size))
    }

    // ---------------------------------------------------------------- isDeafMic

    @Test
    fun `the watchdog does not fire on a quiet working mic but does fire on a digitally silent one`() {
        // A quiet room, held open well past the elapsed threshold: room noise (peak 250, from
        // the fixture above) never gets anywhere near SILENCE_PEAK_THRESHOLD, so this must NOT
        // fire no matter how long the window has been open - the exact trap the ticket names.
        val quietWorkingMic = MicSignal.isDeafMic(
            elapsedMs = MicSignal.DEAF_MIC_ELAPSED_MS * 3,
            peakSinceOpen = 250,
        )
        assertFalse("a quiet room should never trip the watchdog", quietWorkingMic)

        // Digital silence for the same duration: this is the actual fault the ticket exists for.
        val deafMic = MicSignal.isDeafMic(
            elapsedMs = MicSignal.DEAF_MIC_ELAPSED_MS,
            peakSinceOpen = 0,
        )
        assertTrue("digital silence past the elapsed threshold should trip the watchdog", deafMic)
    }

    @Test
    fun `the watchdog does not fire before the elapsed threshold even at zero peak`() {
        // A mic that just opened and has read one silent buffer is not a fault yet - it is the
        // ordinary gap before speech starts. Firing here would be the same "quiet equals broken"
        // mistake the ticket warns against, just measured in milliseconds instead of a whole turn.
        val justOpened = MicSignal.isDeafMic(elapsedMs = 200L, peakSinceOpen = 0)
        assertFalse("a freshly opened mic must not trip the watchdog immediately", justOpened)
    }

    @Test
    fun `the watchdog does not fire on speech-level audio no matter how long it is held open`() {
        val speaking = MicSignal.isDeafMic(
            elapsedMs = MicSignal.DEAF_MIC_ELAPSED_MS * 5,
            peakSinceOpen = 12000,
        )
        assertFalse(speaking)
    }

    @Test
    fun `isDeafMic honors explicit thresholds independent of the defaults`() {
        // A peak of 40 fails a custom threshold of 30 but passes the default of 50 - proves the
        // thresholds are real parameters, not constants baked into the function body.
        assertTrue(MicSignal.isDeafMic(elapsedMs = 10_000L, peakSinceOpen = 40, peakThreshold = 50))
        assertFalse(MicSignal.isDeafMic(elapsedMs = 10_000L, peakSinceOpen = 40, peakThreshold = 30))
    }
}
