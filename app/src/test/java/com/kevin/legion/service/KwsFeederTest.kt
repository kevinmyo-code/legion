package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 18 crash fix: the feed/decode loop and the circuit breaker, no native code. */
class KwsFeederTest {
    /** Becomes ready every [frame] samples accepted, like a chunked streaming encoder. */
    private class FakePort(val frame: Int, val hitOnDecode: Int = -1) : KeywordStreamPort {
        var pending = 0
        var decodes = 0
        var fresh = 0
        var decodedWhileNotReady = false
        override fun accept(samples: FloatArray) { pending += samples.size }
        override fun isReady() = pending >= frame
        override fun decode() {
            if (!isReady()) decodedWhileNotReady = true
            pending -= frame
            decodes++
        }
        override fun keyword() = if (decodes == hitOnDecode) "hey" else ""
        override fun fresh() { pending = 0; fresh++ }
    }

    @Test
    fun `never decodes without a full frame and keeps the remainder`() {
        val p = FakePort(frame = 100)
        assertFalse(KwsFeeder.feed(p, FloatArray(250)))
        assertEquals(2, p.decodes)
        assertEquals(50, p.pending)
        assertFalse(p.decodedWhileNotReady)
        KwsFeeder.feed(p, FloatArray(30))
        assertEquals(2, p.decodes)
    }

    @Test
    fun `a hit takes a fresh stream and stops decoding`() {
        val p = FakePort(frame = 100, hitOnDecode = 1)
        assertTrue(KwsFeeder.feed(p, FloatArray(500)))
        assertEquals(1, p.decodes)
        assertEquals(1, p.fresh)
    }

    private class MemStore : TwoStageBreaker.Store {
        override var markerSet = false
        override var tripped = false
    }

    @Test
    fun `a leftover marker trips the breaker once`() {
        val store = MemStore().apply { markerSet = true }
        assertFalse(TwoStageBreaker(store).shouldStart())
        assertTrue(store.tripped)
        assertFalse(store.markerSet)
    }

    @Test
    fun `marker clears after enough clean decodes or a clean release`() {
        val store = MemStore()
        val b = TwoStageBreaker(store)
        assertTrue(b.shouldStart())
        b.markStarted()
        repeat(TwoStageBreaker.OK_DECODES_TO_CLEAR - 1) { b.onDecodeOk() }
        assertTrue(store.markerSet)
        b.onDecodeOk()
        assertFalse(store.markerSet)
        b.markStarted()
        b.onCleanRelease()
        assertFalse(store.markerSet)
        assertFalse(store.tripped)
    }
}
