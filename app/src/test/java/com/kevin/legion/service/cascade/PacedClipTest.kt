package com.kevin.legion.service.cascade

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wait line fed a slice at a time ([PacedClip]): the answer's audio cuts it with more than
 * [DeadAir.FINISH_MAX_MS] left, so the answer is never held behind a whole line. A fake player in
 * virtual time plays one ms of audio per ms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PacedClipTest {
    private val rate = 24_000
    private val bytesPerMs = rate * 2 / 1_000

    private inner class FakePlayer(private val scope: TestScope) {
        var writtenMs = 0L
        var maxAheadMs = 0L
        private val start = scope.testScheduler.currentTime

        fun ahead(): Long = (writtenMs - (scope.testScheduler.currentTime - start)).coerceAtLeast(0)

        fun write(slice: ByteArray): Boolean {
            writtenMs += slice.size / bytesPerMs
            maxAheadMs = maxOf(maxAheadMs, ahead())
            return true
        }

        fun now(): Long = scope.testScheduler.currentTime - start
    }

    private suspend fun TestScope.playLine(lineMs: Long, answerAtMs: Long?): Pair<PacedClip.End, FakePlayer> {
        val p = FakePlayer(this)
        val end = PacedClip.play(
            ByteArray((lineMs * bytesPerMs).toInt()),
            rate,
            write = { p.write(it) },
            aheadMs = { p.ahead() },
            cut = { left -> DeadAir.cutPlaying(answerAtMs != null && p.now() >= answerAtMs, left) },
        )
        return end to p
    }

    @Test
    fun `the answer's audio in hand early cuts the line, and holds the answer only for what is queued`() = runTest {
        val (end, p) = playLine(lineMs = 1_200, answerAtMs = 300)
        assertEquals(PacedClip.End.CUT, end)
        // What still plays after the cut: at most the lead plus one slice.
        assertTrue("ahead ${p.ahead()}", p.ahead() <= PacedClip.LEAD_MS + PacedClip.SLICE_MS)
        assertTrue("cut near 300 ms, at ${p.now()}", p.now() in 300L..340L)
        assertTrue(p.writtenMs < 1_200)
    }

    @Test
    fun `a line with little left when the answer comes finishes`() = runTest {
        val (end, p) = playLine(lineMs = 1_200, answerAtMs = 1_000)
        assertEquals(PacedClip.End.WHOLE, end)
        assertEquals(1_200L, p.writtenMs)
    }

    @Test
    fun `with no answer the whole line plays, never more than the lead ahead`() = runTest {
        val (end, p) = playLine(lineMs = 900, answerAtMs = null)
        assertEquals(PacedClip.End.WHOLE, end)
        assertEquals(900L, p.writtenMs)
        assertTrue("max ahead ${p.maxAheadMs}", p.maxAheadMs <= PacedClip.LEAD_MS + PacedClip.SLICE_MS)
    }

    @Test
    fun `a player that refuses a write (a flush, a release) stops the line`() = runTest {
        val pcm = ByteArray(bytesPerMs * 500)
        val end = PacedClip.play(pcm, rate, write = { false }, aheadMs = { 0L }, cut = { false })
        assertEquals(PacedClip.End.STOPPED, end)
    }
}
