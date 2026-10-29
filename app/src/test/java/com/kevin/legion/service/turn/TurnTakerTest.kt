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
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun log(tag: String, message: String) { logs += "$tag $message" }
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

    /** 2026-10-28: the gate fires up to a 512 ms span after onset, so the wire pre-roll covers it. */
    @Test
    fun `a barge-in sends the gate's whole span as pre-roll`() {
        val vad = ScriptedVad(0.95f)
        val sink = RecordingSink()
        val t = TurnTaker(TurnConfig(true, true, false, "test"), vad, null, sink)
        t.onReplyStart()
        repeat(10) { t.feed(quiet, quiet.size, playing = true) }
        while (sink.barged == 0) t.feed(loud(), 3_200, playing = true)
        assertEquals(listOf("start", "audio"), sink.wire.toList())
        assertEquals(TurnTaker.BARGE_PRE_ROLL_MS * TurnTaker.BYTES_PER_MS, sink.bytes)
        // Equal since ticket 08 item 3: a normal start after a drain needs the same reach.
        assertTrue(TurnTaker.BARGE_PRE_ROLL_MS >= TurnTaker.PRE_ROLL_MS)
        val line = sink.logs.single { it.startsWith("${TurnTaker.LATENCY_TAG} pre_roll_ms=") }
        val bytes = TurnTaker.BARGE_PRE_ROLL_MS * TurnTaker.BYTES_PER_MS
        assertTrue(line, line.contains("pre_roll_bytes=$bytes barge=true"))
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

    @Test
    fun `each reply logs armed at start and one reply_end line with its stats`() {
        val vad = ScriptedVad(0.95f)
        val sink = RecordingSink()
        val t = TurnTaker(TurnConfig(false, true, false, "test"), vad, null, sink)
        t.onReplyStart()
        repeat(4) { t.feed(loud(), 3_200, playing = true) }
        t.onReplyEnd()
        t.onReplyEnd() // flush then drain: the second end is silent
        val barge = sink.logs.filter { it.startsWith(TurnTaker.BARGE_TAG) }
        assertEquals("LIVE_BARGE_IN armed=true disabled=false", barge.first())
        assertTrue(barge[1].startsWith("LIVE_BARGE_IN fire "))
        assertEquals(
            "LIVE_BARGE_IN reply_end fired=true windows=8 speech_windows=8 loud_windows=8 " +
                "qualifying_windows=8 max_prob=0.95 max_level=18000 longest_run_ms=256 best_span=8 " +
                "echo_floor=12000 threshold=18000",
            barge[2],
        )
        assertEquals(3, barge.size)
    }

    @Test
    fun `a reply still open when the next starts is ended and logged first`() {
        val sink = RecordingSink()
        val t = TurnTaker(TurnConfig(false, true, false, "test"), ScriptedVad(0.1f), null, sink)
        t.onReplyStart()
        t.feed(quiet, quiet.size, playing = true)
        t.onReplyStart()
        val barge = sink.logs.filter { it.startsWith(TurnTaker.BARGE_TAG) }
            .map { it.substringAfter(" ").substringBefore(" ") }
        assertEquals(listOf("armed=true", "reply_end", "armed=true"), barge)
    }

    /** 100 ms at a peak of 9,784 (0x2638), run 6's learned "echo" that was the user. */
    private fun mid() = ByteArray(3_200).also { b ->
        for (i in b.indices step 2) {
            b[i] = 0x38
            b[i + 1] = 0x26
        }
    }

    /** Run 6: the activity end arms the gate's guard, so the next reply does not learn the user. */
    @Test
    fun `the reply after an activity end does not learn the user still talking`() {
        val vad = ScriptedVad(0.9f)
        val sink = RecordingSink()
        val t = TurnTaker(TurnConfig(true, true, false, "test"), vad, null, sink)
        repeat(5) { t.feed(quiet, quiet.size, playing = false) }
        vad.next = 0.05f
        while (sink.endReason == null) t.feed(quiet, quiet.size, playing = false)
        t.onReplyStart()
        vad.next = 0.9f
        repeat(3) { t.feed(mid(), 3_200, playing = true) } // still talking, under the default 18,000
        vad.next = 0.05f
        repeat(4) { t.feed(quiet, quiet.size, playing = true) } // the reply's echo
        t.onReplyEnd()
        val end = sink.logs.single { it.startsWith("${TurnTaker.BARGE_TAG} reply_end") }
        assertTrue(end, end.contains("echo_floor=0 threshold=3000"))
        assertEquals(0, sink.barged)
    }

    /** Ticket 08 item 3: a normal start right after a reply drains logs how far its pre-roll reached. */
    @Test
    fun `a normal start after a drain logs its pre-roll next to the barge one`() {
        val vad = ScriptedVad(0.05f)
        val sink = RecordingSink()
        val t = TurnTaker(TurnConfig(true, true, false, "test"), vad, null, sink)
        t.onReplyStart()
        repeat(8) { t.feed(quiet, quiet.size, playing = true) }
        t.onReplyEnd()
        vad.next = 0.9f // the word starts on the first buffer after the drain
        repeat(3) { t.feed(quiet, quiet.size, playing = false) }
        val line = sink.logs.single { it.startsWith("${TurnTaker.LATENCY_TAG} pre_roll_ms=") }
        val f = Regex("(\\w+)=(\\S+)").findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("false", f["barge"])
        assertEquals((TurnTaker.PRE_ROLL_MS * TurnTaker.BYTES_PER_MS).toString(), f["pre_roll_bytes"])
        assertEquals("224", f["pre_roll_onset_ms"])
        assertEquals(f["pre_roll_onset_ms"], f["pre_roll_after_reply_ms"])
        val lead = f.getValue("pre_roll_lead_ms").toInt()
        assertEquals(TurnTaker.PRE_ROLL_MS - 224 - f.getValue("pre_roll_tail_ms").toInt(), lead)
        assertTrue(lead >= 220)
    }
}
