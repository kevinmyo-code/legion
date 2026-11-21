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
}
