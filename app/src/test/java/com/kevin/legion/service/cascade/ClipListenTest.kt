package com.kevin.legion.service.cascade

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClipListen] with synthetic frames (blind review M1, 2026-11-19): his audio through a half-duplex
 * backchannel is kept, and only audio after the clip decides a resume. Frames are 100 ms (3,200 bytes).
 */
class ClipListenTest {
    private val frame = 3_200
    private fun f(v: Int) = ByteArray(frame) { v.toByte() }
    private val first = ByteArray(6_400) { 9 }

    @Test
    fun `resumed during the clip, the first half and every clip frame open the continued turn`() {
        val l = ClipListen(first)
        repeat(4) { l.onFrame(f(1), frame, playing = true) } // clip + settle: his words, kept, never judged
        assertFalse("nothing decides while the clip plays", l.resumes())
        repeat(3) { l.onFrame(f(2), frame, playing = false) } // after the clip: the detector starts here
        assertTrue(l.resumes())
        // The pre-roll (544 ms = 17,408 bytes) repeats 17,408 - 9,600 = 7,808 bytes of the clip audio.
        val p = l.prefix(preRollBytes = 17_408)
        assertArrayEquals(first, p.copyOfRange(0, first.size))
        assertEquals(first.size + 4 * frame - 7_808, p.size)
        assertTrue(p.copyOfRange(first.size, p.size).all { it == 1.toByte() })
    }

    @Test
    fun `a start well after the clip is past the window and leaves the reply alone`() {
        val l = ClipListen(first)
        l.onFrame(f(1), frame, playing = true)
        val windowFrames = (ClipListen.RESUME_WINDOW_BYTES / frame).toInt()
        repeat(windowFrames) { l.onFrame(f(0), frame, playing = false) }
        assertTrue(l.resumes())
        l.onFrame(f(0), frame, playing = false)
        assertFalse(l.resumes())
        assertTrue(l.expired)
    }

    @Test
    fun `echo only, the clip's own sound is never counted as after, and alone decides nothing`() {
        val l = ClipListen(first)
        repeat(10) { l.onFrame(f(5), frame, playing = true) }
        assertEquals(0L, l.bytesAfter)
        assertFalse(l.clipOver)
        assertFalse(l.resumes())
        assertFalse(l.expired)
    }

    @Test
    fun `a start long after the pre-roll keeps all the clip audio, and the keep is capped`() {
        val l = ClipListen(ByteArray(0), capBytes = 2 * frame)
        repeat(3) { l.onFrame(f(1), frame, playing = true) }
        repeat(10) { l.onFrame(f(0), frame, playing = false) }
        assertEquals(2 * frame, l.prefix(preRollBytes = 17_408).size)
    }
}
