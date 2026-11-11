package com.kevin.legion.service.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2 seam: the first turn is held on the phone; a miss reaches Live whole and in order. */
class HeldTurnSinkTest {

    private class Recorder : TurnTaker.Sink {
        val events = mutableListOf<String>()
        val audio = java.io.ByteArrayOutputStream()
        override fun sendActivityStart() { events += "start" }
        override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {
            events += "audio"
            audio.write(pcm, offset, length)
        }
        override fun sendActivityEnd() { events += "end" }
        override fun onUserTurnStart(bargeIn: Boolean) { events += "turnStart" }
        override fun onEndOfTurn(end: EndOfTurnPolicy.Action.EndActivity) = Unit
        override fun onBargeIn(fire: BargeInGate.Event.Fire) = Unit
        override fun onBargeInDisabled(selfInterrupts: Int) = Unit
        override fun onLocalSpeech() = Unit
        override fun onSileroFailed(reason: String) = Unit
        override fun onSmartTurnFailed(reason: String) = Unit
        override fun log(tag: String, message: String) = Unit
    }

    private fun speak(sink: TurnTaker.Sink, bytes: ByteArray) {
        sink.sendActivityStart()
        sink.sendAudio(bytes, 0, bytes.size / 2)
        sink.sendAudio(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
        sink.sendActivityEnd()
    }

    @Test
    fun `a handled first turn never reaches Live`() {
        val live = Recorder()
        var heard: ByteArray? = null
        val sink = HeldTurnSink(live, { pcm -> heard = pcm; true }, armed = true)
        val words = ByteArray(10_000) { it.toByte() }
        speak(sink, words)
        assertEquals(emptyList<String>(), live.events)
        assertEquals(words.toList(), heard!!.toList())
    }

    @Test
    fun `a miss replays the same audio to Live, start to end, in order`() {
        val live = Recorder()
        val sink = HeldTurnSink(live, { false }, armed = true)
        val words = ByteArray(10_000) { (it * 7).toByte() }
        speak(sink, words)
        assertEquals("start", live.events.first())
        assertEquals("end", live.events.last())
        assertTrue(live.events.drop(1).dropLast(1).all { it == "audio" })
        assertEquals(words.toList(), live.audio.toByteArray().toList())
    }

    @Test
    fun `only the first turn is held`() {
        val live = Recorder()
        var decisions = 0
        val sink = HeldTurnSink(live, { decisions++; true }, armed = true)
        speak(sink, ByteArray(100))
        speak(sink, ByteArray(100))
        assertEquals(1, decisions)
        assertEquals(listOf("start", "audio", "audio", "end"), live.events)
    }

    @Test
    fun `a decision that throws is a miss, and the turn still reaches Live`() {
        val live = Recorder()
        val sink = HeldTurnSink(live, { error("recogniser crashed") }, armed = true)
        speak(sink, ByteArray(4_000))
        assertEquals("end", live.events.last())
    }

    @Test
    fun `unarmed it is a plain pass-through`() {
        val live = Recorder()
        val sink = HeldTurnSink(live, { true }, armed = false)
        speak(sink, ByteArray(100))
        sink.onUserTurnStart(false)
        assertEquals(listOf("start", "audio", "audio", "end", "turnStart"), live.events)
    }
}
