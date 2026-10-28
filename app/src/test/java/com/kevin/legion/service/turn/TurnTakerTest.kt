package com.kevin.legion.service.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The glue: what reaches the socket, in what order. Silero and Smart Turn are fakes. */
class TurnTakerTest {
    private class ScriptedVad(var next: Float = 0f) : SpeechProbStream {
        override fun prob(window: FloatArray) = next
        override fun reset() = Unit
    }

    private class RecordingSink : TurnTaker.Sink {
        val wire = java.util.Collections.synchronizedList(mutableListOf<String>())
        var bytes = 0
        val ended = CountDownLatch(1)
        @Volatile var endReason: EndOfTurnPolicy.Reason? = null
        var barged = 0
        override fun sendActivityStart() { wire += "start" }
        override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {
            wire += "audio"
            bytes += length
        }
        override fun sendActivityEnd() { wire += "end" }
        override fun onUserTurnStart(bargeIn: Boolean) = Unit
        override fun onEndOfTurn(end: EndOfTurnPolicy.Action.EndActivity) {
            endReason = end.reason
            ended.countDown()
        }
        override fun onBargeIn(fire: BargeInGate.Event.Fire) { barged++ }
        override fun onBargeInDisabled(selfInterrupts: Int) = Unit
        override fun onLocalSpeech() = Unit
        override fun onSileroFailed(reason: String) = Unit
        override fun onSmartTurnFailed(reason: String) = Unit
        override fun log(tag: String, message: String) = Unit
    }

    private val quiet = ByteArray(3_200) // 100 ms of digital silence

    /** 100 ms at a peak of about 18,000. */
    private fun loud() = ByteArray(3_200).also { b ->
        for (i in b.indices step 2) {
            b[i] = 0x50
            b[i + 1] = 0x46
        }
    }

    @Test
    fun `manual activity sends start, the pre-roll, the turn, then end on silence`() {
        val vad = ScriptedVad()
        val sink = RecordingSink()
        val t = TurnTaker(TurnConfig(true, false, false, "test"), vad, null, sink)
        repeat(5) { assertEquals(0, t.feed(quiet, quiet.size, playing = false)) }
        vad.next = 0.9f
        repeat(5) { t.feed(quiet, quiet.size, playing = false) }
        vad.next = 0.05f
        repeat(10) { t.feed(quiet, quiet.size, playing = false) }
        assertEquals("start", sink.wire.first())
        assertEquals("audio", sink.wire[1])
        assertEquals("end", sink.wire.last())
        assertEquals(EndOfTurnPolicy.Reason.FALLBACK, sink.endReason)
        assertTrue(sink.bytes >= TurnTaker.PRE_ROLL_MS * 32)
        val before = sink.wire.size
        t.feed(quiet, quiet.size, playing = false)
        assertEquals(before, sink.wire.size)
    }

    @Test
    fun `smart turn saying not done holds the turn past the base`() {
        // Warm the shared inference thread's feature extractor first: a cold JVM can take longer
        // than the 700 ms base on the first check, which (correctly) ends the turn at the base.
        val warm = CountDownLatch(1)
        val warmVad = ScriptedVad(0.9f)
        val warmTaker = TurnTaker(TurnConfig(true, false, true, "warm"), warmVad, TurnEndModel {
            warm.countDown()
            0.9f
        }, RecordingSink())
        repeat(5) { warmTaker.feed(quiet, quiet.size, playing = false) }
        warmVad.next = 0.05f
        repeat(3) { warmTaker.feed(quiet, quiet.size, playing = false) }
        warm.await(10, TimeUnit.SECONDS)

        val vad = ScriptedVad()
        val sink = RecordingSink()
        var calls = 0
        // Each check takes 120 ms; "not done" until well past the 700 ms base, then "done".
        val model = TurnEndModel {
            Thread.sleep(120)
            calls++
            if (calls < 8) 0.1f else 0.9f
        }
        val t = TurnTaker(TurnConfig(true, false, true, "test"), vad, model, sink)
        vad.next = 0.9f
        repeat(5) { t.feed(quiet, quiet.size, playing = false) }
        vad.next = 0.05f
        // One buffer at a time with time for the check to come back, like real time.
        for (i in 0 until 30) {
            t.feed(quiet, quiet.size, playing = false)
            if (sink.ended.await(150, TimeUnit.MILLISECONDS)) break
        }
        assertEquals(EndOfTurnPolicy.Reason.EXTENDED, sink.endReason)
        assertTrue(calls >= 8)
    }

    @Test
    fun `while the reply plays the echo cannot start a turn, only the gate can`() {
        val vad = ScriptedVad(0.95f)
        val sink = RecordingSink()
        val t = TurnTaker(TurnConfig(true, true, false, "test"), vad, null, sink)
        t.onReplyStart()
        repeat(10) { t.feed(quiet, quiet.size, playing = true) }
        assertTrue(sink.wire.isEmpty())
        repeat(4) { t.feed(loud(), 3_200, playing = true) }
        assertEquals(1, sink.barged)
        assertEquals("start", sink.wire.first())
    }

    @Test
    fun `server VAD with barge-in forwards nothing during the reply until a barge`() {
        val vad = ScriptedVad(0.95f)
        val sink = RecordingSink()
        val t = TurnTaker(TurnConfig(false, true, false, "test"), vad, null, sink)
        assertEquals(3_200, t.feed(quiet, quiet.size, playing = false))
        t.onReplyStart()
        assertEquals(0, t.feed(quiet, quiet.size, playing = true))
        repeat(4) { t.feed(loud(), 3_200, playing = true) }
        assertEquals(1, sink.barged)
        assertTrue(t.feed(quiet, quiet.size, playing = true) > 0)
        assertTrue(sink.wire.none { it == "start" || it == "end" })
    }
}
