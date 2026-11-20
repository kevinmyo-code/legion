package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SttHedge.race] with scripted attempts and short real delays, and [MissedLine]'s rules (Kevin,
 * 2026-11-19, speed plan item 4a). Strings stand in for transcription outcomes: "ok..." is good,
 * "retry" is worth asking again, anything else is a final failure.
 */
class SttHedgeTest {
    private fun good(s: String) = s.startsWith("ok")
    private fun retry(s: String) = s == "retry"

    @Test
    fun `a quick first answer wins alone, no second request`() = runBlocking<Unit> {
        val calls = AtomicInteger(0)
        val r = SttHedge.race(200, 2_000, { calls.incrementAndGet(); "ok-first" }, ::good, ::retry)
        assertEquals(SttHedge.Result.Won("ok-first", 0), r)
        assertEquals(1, calls.get())
    }

    @Test
    fun `a slow first gets a second beside it at the hedge time, and the first good answer wins`() = runBlocking<Unit> {
        val n = AtomicInteger(0)
        val firstCancelled = CompletableDeferred<Unit>()
        val why = Collections.synchronizedList(mutableListOf<SttHedge.Why>())
        val t0 = System.currentTimeMillis()
        val r = SttHedge.race(
            hedgeAfterMs = 100,
            attemptTimeoutMs = 5_000,
            attempt = {
                if (n.getAndIncrement() == 0) {
                    try { awaitCancellation() } finally { firstCancelled.complete(Unit) }
                }
                "ok-second"
            },
            good = ::good,
            worthRetry = ::retry,
            onSecond = { w, _ -> why += w },
        )
        assertEquals(SttHedge.Result.Won("ok-second", 1), r)
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1_000)
        assertEquals(listOf(SttHedge.Why.SLOW), why.toList())
        withTimeout(1_000) { firstCancelled.await() }
    }

    @Test
    fun `the first can still win after the hedge, and the second is cancelled`() = runBlocking<Unit> {
        val n = AtomicInteger(0)
        val secondCancelled = CompletableDeferred<Unit>()
        val r = SttHedge.race(100, 5_000, {
            if (n.getAndIncrement() == 0) {
                delay(250)
                "ok-first"
            } else {
                try { awaitCancellation() } finally { secondCancelled.complete(Unit) }
            }
        }, ::good, ::retry)
        assertEquals(SttHedge.Result.Won("ok-first", 0), r)
        withTimeout(1_000) { secondCancelled.await() }
    }

    @Test
    fun `a failure worth retrying before the hedge asks again at once, a final one does not`() = runBlocking<Unit> {
        val n = AtomicInteger(0)
        val why = mutableListOf<SttHedge.Why>()
        val r = SttHedge.race(5_000, 5_000, { if (n.getAndIncrement() == 0) "retry" else "ok-again" }, ::good, ::retry,
            { w, _ -> why += w })
        assertEquals(SttHedge.Result.Won("ok-again", 1), r)
        assertEquals(listOf(SttHedge.Why.FAILED), why)

        val m = AtomicInteger(0)
        val refused = SttHedge.race(5_000, 5_000, { m.incrementAndGet(); "refused" }, ::good, ::retry)
        assertEquals(SttHedge.Result.Lost("refused"), refused)
        assertEquals(1, m.get())
    }

    @Test
    fun `never more than two requests, and two hangs are a loss with nothing`() = runBlocking<Unit> {
        val n = AtomicInteger(0)
        val r = withTimeout(3_000) {
            SttHedge.race<String>(50, 200, { n.incrementAndGet(); awaitCancellation() }, ::good, ::retry)
        }
        assertEquals(SttHedge.Result.Lost<String>(null), r)
        assertEquals(SttHedge.MAX_REQUESTS, n.get())
    }

    @Test
    fun `the hedge time grows with longer audio, capped`() {
        assertEquals(1_500L, SttHedge.hedgeAfterFor(1_000))
        assertEquals(1_500L, SttHedge.hedgeAfterFor(4_000))
        assertEquals(1_750L, SttHedge.hedgeAfterFor(4_500))
        assertEquals(2_500L, SttHedge.hedgeAfterFor(8_000))
        assertEquals(SttHedge.HEDGE_CAP_MS, SttHedge.hedgeAfterFor(28_000))
        // Always before the attempt's own deadline, so the hedge is what fires on a slow request.
        for (ms in listOf(1_000L, 4_000L, 8_000L, 28_000L)) {
            assertTrue(SttHedge.hedgeAfterFor(ms) < GeminiStt.attemptTimeoutFor(ms))
        }
    }

    // --- MissedLine -----------------------------------------------------------------------------------

    @Test
    fun `silence closes quietly, voice is asked again once, then the line`() {
        assertEquals(MissedLine.OnFailure.SILENT, MissedLine.onFailure(voicedMs = 0, retried = false))
        assertEquals(MissedLine.OnFailure.SILENT, MissedLine.onFailure(MissedLine.SILENT_BELOW_MS - 1, retried = false))
        assertEquals(MissedLine.OnFailure.RETRY, MissedLine.onFailure(MissedLine.SILENT_BELOW_MS, retried = false))
        assertEquals(MissedLine.OnFailure.SAY, MissedLine.onFailure(2_000, retried = true))
        // Nothing kept to measure or send: the line, as before.
        assertEquals(MissedLine.OnFailure.SAY, MissedLine.onFailure(null, retried = false))
    }

    @Test
    fun `the missed line is held over his speech and right after a reply`() {
        assertEquals(MissedLine.Hold.HE_IS_SPEAKING, MissedLine.hold(speakingNow = true, msSinceReplyEnd = null))
        assertEquals(MissedLine.Hold.RIGHT_AFTER_REPLY, MissedLine.hold(false, msSinceReplyEnd = 2))
        assertEquals(MissedLine.Hold.NONE, MissedLine.hold(false, msSinceReplyEnd = MissedLine.AFTER_REPLY_MS))
        assertEquals(MissedLine.Hold.NONE, MissedLine.hold(false, msSinceReplyEnd = null))
    }
}
