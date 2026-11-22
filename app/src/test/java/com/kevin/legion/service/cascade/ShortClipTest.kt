@file:Suppress("MaxLineLength") // test fixtures read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.service.ListenCue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A short clip on a streaming track must start at once (2026-11-21, the chime written after the close's
 * flush was never mixed: the track waited for its buffer to fill). Pure rule, then the player's use of it
 * on a device without the start threshold and on one with it.
 */
@RunWith(RobolectricTestRunner::class)
class ShortClipTest {
    private val chime = ListenCue.closeChimePcm()
    private val bufferFrames = 12_000

    @Test
    fun `from API 31 the track starts at one frame and a clip is written as it is`() {
        assertEquals(1, ShortClip.startThreshold(31))
        assertEquals(1, ShortClip.startThreshold(34))
        assertSame(chime, ShortClip.startable(chime, bufferFrames, 34))
    }

    @Test
    fun `below API 31 a clip shorter than the buffer is padded with silence so it starts`() {
        assertNull(ShortClip.startThreshold(30))
        val padded = ShortClip.startable(chime, bufferFrames, 30)
        assertEquals(bufferFrames * 2, padded.size)
        assertArrayEquals(chime, padded.copyOf(chime.size))
        assertTrue(padded.copyOfRange(chime.size, padded.size).all { it == 0.toByte() })
        // A clip as long as the buffer, or longer, starts by itself.
        val long = ByteArray(bufferFrames * 2 + 10) { 3 }
        assertSame(long, ShortClip.startable(long, bufferFrames, 30))
    }

    @Test
    @Config(sdk = [30])
    fun `the player pads the chime on a device without the threshold`() {
        val p = ReplyPlayer(ListenCue.SAMPLE_RATE, { false })
        val out = p.startable(chime)
        assertTrue("${out.size} > ${chime.size}", out.size > chime.size)
        assertEquals(0, (out.size / 2) % 1)
    }

    @Test
    @Config(sdk = [34])
    fun `the player writes the chime as it is where the track starts at one frame`() {
        val p = ReplyPlayer(ListenCue.SAMPLE_RATE, { false })
        assertSame(chime, p.startable(chime))
    }

    /**
     * AudioFlinger's rule for a streaming track that has just started, been flushed or restarted after
     * an underrun: nothing of it is mixed until the frames waiting reach its start threshold.
     */
    private class FillingTrack(private val threshold: Int) {
        private var waiting = 0
        var consumed = 0
            private set

        fun write(frames: Int) {
            waiting += frames
        }

        fun mix() {
            if (waiting >= threshold) {
                consumed += waiting
                waiting = 0
            }
        }
    }

    @Test
    fun `a sub-threshold clip after idle is consumed with the rule, and never without it`() {
        val clipFrames = 254 * 24 // the 14:41 "Right.", 254 ms at 24 kHz
        val clip = ByteArray(clipFrames * 2) { 1 }
        // The shipped build: default threshold (the buffer), clip written as it is. Never mixed.
        FillingTrack(bufferFrames).apply {
            write(clip.size / 2)
            repeat(10) { mix() }
            assertEquals(0, consumed)
        }
        // API 31+: the threshold is one frame. Mixed at once.
        FillingTrack(ShortClip.startThreshold(34)!!).apply {
            write(ShortClip.startable(clip, bufferFrames, 34).size / 2)
            mix()
            assertEquals(clipFrames, consumed)
        }
        // Below API 31: default threshold, but the clip is padded to the buffer. Mixed at once.
        FillingTrack(bufferFrames).apply {
            write(ShortClip.startable(clip, bufferFrames, 30).size / 2)
            mix()
            assertTrue("$consumed", consumed >= clipFrames)
        }
    }
}
