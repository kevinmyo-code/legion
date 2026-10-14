package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmJitterFeederTest {

    /**
     * Fake streaming track. A write into a full PLAYING track "blocks" by advancing the head
     * (what the real track does while the caller waits). [scripted] overrides write results.
     */
    private class FakeSink(override val capacityFrames: Int = 24_000) : PcmSink {
        var head = 0L
        var written = 0L
        var playing = false
        var plays = 0
        var pauses = 0
        var underruns = 0
        val scripted = ArrayDeque<Int>()
        val writeSizes = mutableListOf<Int>()
        override val isPlaying get() = playing
        override fun headFrames() = head
        override fun underrunCount() = underruns
        override fun play() { playing = true; plays++ }
        override fun pause() { playing = false; pauses++ }
        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            writeSizes += size
            val n = if (scripted.isNotEmpty()) scripted.removeFirst() else {
                val free = (capacityFrames - (written - head)) * 2
                if (playing && size > free) head += (size - free) / 2
                else if (!playing) check(size <= free) { "would block forever on a paused track" }
                size
            }
            if (n > 0) written += n / 2
            return n
        }
        /** Speaker consumes everything queued (the track ran dry). */
        fun drain() { if (playing) head = written }
    }

    private fun pcm(frames: Int) = ByteArray(frames * 2)

    @Test
    fun turnStaysPausedUntilPrimeThresholdIsQueued() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(2_000))
        assertFalse(sink.playing)
        feeder.feed(pcm(2_000))
        assertFalse(sink.playing)
        feeder.feed(pcm(2_000)) // crosses 4800
        assertTrue(sink.playing)
        assertEquals(6_000L, feeder.framesWritten)
    }

    @Test
    fun primingSplitsTheChunkAtTheThresholdThenWritesTheRest() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(10_000))
        assertEquals(listOf(9_600, 10_400), sink.writeSizes)
        assertTrue(sink.playing)
        assertEquals(10_000L, feeder.framesWritten)
    }

    @Test
    fun turnStartPausesAnIdlePlayingTrackToPrime() {
        val sink = FakeSink().apply { playing = true }
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(1_000))
        assertFalse(sink.playing)
        assertEquals(1, sink.pauses)
    }

    @Test
    fun midTurnUnderrunPausesAndRebuffers() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(6_000))
        assertTrue(sink.playing)
        sink.drain() // next chunk is late: track ran dry
        sink.underruns = 1
        feeder.feed(pcm(1_000))
        assertFalse("re-priming after the underrun", sink.playing)
        val stats = feeder.endOfTurn()!!
        assertEquals(1, stats.rebuffers)
        assertEquals(1, stats.halUnderruns)
        assertEquals(2, stats.chunks)
        assertEquals(7_000L * 1000 / 24_000, stats.audioMs)
    }

    @Test
    fun chunkArrivingWhileStillBufferedDoesNotRebuffer() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(6_000))
        sink.head = 5_000 // 1000 frames still queued
        feeder.feed(pcm(1_000))
        assertTrue(sink.playing)
        assertEquals(0, feeder.endOfTurn()!!.rebuffers)
    }

    @Test
    fun endOfTurnReleasesAShortReplyStuckBelowThreshold() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(1_200))
        assertFalse(sink.playing)
        assertTrue(feeder.endOfTurn() != null)
        assertTrue(sink.playing)
    }

    @Test
    fun endOfTurnWithoutAudioReportsNothing() {
        val feeder = PcmJitterFeeder(FakeSink(), primeFrames = 4_800, sampleRate = 24_000)
        assertNull(feeder.endOfTurn())
    }

    @Test
    fun partialWritesAreRetriedForTheRemainder() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(6_000)) // primes and plays, 6000 frames buffered
        sink.writeSizes.clear()
        sink.scripted.addAll(listOf(400, 0, 600, 1_000))
        feeder.feed(pcm(1_000))
        assertEquals(listOf(2_000, 1_600, 1_600, 1_000), sink.writeSizes)
        assertEquals(7_000L, feeder.framesWritten)
        assertEquals(0, feeder.endOfTurn()!!.droppedBytes)
    }

    @Test
    fun errorWriteDropsTheRestAndCountsIt() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(6_000))
        sink.scripted.addAll(listOf(500, -3))
        feeder.feed(pcm(1_000))
        assertEquals(1_500, feeder.endOfTurn()!!.droppedBytes)
    }

    @Test
    fun repeatedZeroWritesGiveUpInsteadOfSpinning() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(6_000))
        repeat(PcmJitterFeeder.MAX_ZERO_WRITES) { sink.scripted.add(0) }
        feeder.feed(pcm(1_000))
        assertEquals(2_000, feeder.endOfTurn()!!.droppedBytes)
    }

    @Test
    fun staleChunkIsAbandonedWithoutCountingDropped() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        var stale = false
        sink.scripted.add(1_000)
        feeder.feed(pcm(1_000)) { stale.also { stale = true } }
        assertEquals(500L, feeder.framesWritten)
        val stats = feeder.onFlushed()!!
        assertEquals(0, stats.droppedBytes)
        assertEquals(0L, feeder.framesWritten)
    }

    @Test
    fun oddTrailingByteIsDroppedNotWritten() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(ByteArray(2_001))
        assertEquals(1_000L, feeder.framesWritten)
        assertEquals(1, feeder.endOfTurn()!!.droppedBytes)
    }

    @Test
    fun primeIsClampedToHalfTheTrackSoAPausedWriteCannotBlock() {
        val sink = FakeSink(capacityFrames = 4_000)
        val feeder = PcmJitterFeeder(sink, primeFrames = 10_000, sampleRate = 24_000)
        assertEquals(2_000, feeder.primeFrames)
        feeder.feed(pcm(12_000)) // FakeSink throws if a paused write exceeds free space
        assertTrue(sink.playing)
    }

    @Test
    fun writesAreCappedAtHalfTheTrackSoABargeInFlushCannotWedgeThem() {
        val sink = FakeSink(capacityFrames = 4_000)
        val feeder = PcmJitterFeeder(sink, primeFrames = 1_000, sampleRate = 24_000)
        feeder.feed(pcm(9_000))
        assertTrue(sink.writeSizes.all { it <= 4_000 })
        assertEquals(9_000L, feeder.framesWritten)
    }

    @Test
    fun flushResetsSoTheNextChunkStartsANewPrimedTurn() {
        val sink = FakeSink()
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        feeder.feed(pcm(6_000))
        sink.playing = false; sink.head = 0; sink.written = 0 // pause + flush
        assertTrue(feeder.onFlushed() != null)
        assertNull(feeder.onFlushed())
        feeder.feed(pcm(1_000))
        assertFalse(sink.playing)
        assertEquals(0, feeder.endOfTurn()!!.rebuffers)
    }

    @Test
    fun bufferedFramesNeverNegative() {
        val sink = FakeSink().apply { head = 99 }
        val feeder = PcmJitterFeeder(sink, primeFrames = 4_800, sampleRate = 24_000)
        assertEquals(0L, feeder.bufferedFrames())
    }
}
