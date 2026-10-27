package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietWindowTest {

    @Test fun `recent local speech extends the window`() {
        assertTrue(QuietWindow.shouldExtend(msSinceLocalSpeech = 200, extensionsUsed = 0))
        assertTrue(QuietWindow.shouldExtend(msSinceLocalSpeech = QuietWindow.RECENT_MS, extensionsUsed = 1))
    }

    @Test fun `no speech or old speech parks as before`() {
        assertFalse(QuietWindow.shouldExtend(msSinceLocalSpeech = null, extensionsUsed = 0))
        assertFalse(QuietWindow.shouldExtend(msSinceLocalSpeech = QuietWindow.RECENT_MS + 1, extensionsUsed = 0))
    }

    @Test fun `extensions are bounded so road noise cannot hold the session open`() {
        assertEquals(2, QuietWindow.MAX_EXTENSIONS)
        assertFalse(QuietWindow.shouldExtend(msSinceLocalSpeech = 0, extensionsUsed = QuietWindow.MAX_EXTENSIONS))
    }

    @Test fun `peak reads signed little-endian samples`() {
        // 0x7FFF, then -32768 (0x8000), then -5 (0xFFFB).
        val buf = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte(), 0xFB.toByte(), 0xFF.toByte())
        assertEquals(32768, QuietWindow.peakAmplitude(buf, 6))
        assertEquals(32767, QuietWindow.peakAmplitude(buf, 2))
        assertEquals(5, QuietWindow.peakAmplitude(buf.copyOfRange(4, 6), 2))
        assertEquals(0, QuietWindow.peakAmplitude(buf, 1))
    }
}
