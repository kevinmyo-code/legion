package com.kevin.legion.books

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-aloud's rules with a fake TTS engine and a fake audio focus (no speech engine, no Context):
 * start, pause, speed, stop on focus loss, and every refusal said in words. The scope is Unconfined,
 * so each launch runs to its first suspension before the call returns.
 */
class ReadAloudControllerTest {
    private class FakeEngine : ReadAloudEngine {
        override val playing = MutableStateFlow(false)
        override val status = MutableStateFlow<EngineStatus>(EngineStatus.Ready)
        override val utterance = MutableStateFlow<String?>(null)
        var appliedSpeed = 1.0f
        var closed = false

        override fun play() {
            playing.value = true
        }

        override fun pause() {
            playing.value = false
        }

        override fun setSpeed(speed: Float) {
            appliedSpeed = speed
        }

        override fun close() {
            closed = true
            playing.value = false
        }
    }

    private class FakeFocus(var grant: Boolean = true) : AudioFocusGate {
        var onLost: (() -> Unit)? = null
        var abandoned = 0

        override fun request(onLost: () -> Unit): Boolean {
            this.onLost = onLost
            return grant
        }

        override fun abandon() {
            abandoned++
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun controller(
        engine: FakeEngine = FakeEngine(),
        focus: FakeFocus = FakeFocus(),
        result: EngineResult? = null,
        starts: MutableList<String?> = mutableListOf(),
    ) = ReadAloudController(scope, focus) { start ->
        starts += start
        result ?: EngineResult.Ready(engine)
    }

    @Test fun `start begins playing from the given sentence and says it is active`() {
        val engine = FakeEngine()
        val starts = mutableListOf<String?>()
        val c = controller(engine, starts = starts)
        c.start("{locator}")
        assertEquals(listOf<String?>("{locator}"), starts)
        assertTrue(c.state.value.active)
        assertTrue(c.state.value.playing)
        assertNull(c.state.value.message)
    }

    @Test fun `playing mirrors the engine, never the button`() {
        val engine = FakeEngine()
        val c = controller(engine)
        c.start(null)
        engine.playing.value = false
        assertFalse("a silent engine must not read as reading", c.state.value.playing)
    }

    @Test fun `pause keeps the session and a second toggle resumes it`() {
        val engine = FakeEngine()
        val c = controller(engine)
        c.start(null)
        c.pause()
        assertFalse(c.state.value.playing)
        assertTrue("paused is not stopped", c.state.value.active)
        c.toggle(null)
        assertTrue(c.state.value.playing)
    }

    @Test fun `losing audio focus pauses and says why`() {
        val engine = FakeEngine()
        val focus = FakeFocus()
        val c = controller(engine, focus)
        c.start(null)
        focus.onLost!!.invoke()
        assertFalse(engine.playing.value)
        assertEquals(ReadAloudController.FOCUS_LOST, c.state.value.message)
        assertTrue("still a session the user can resume", c.state.value.active)
    }

    @Test fun `focus refused means nothing started and the engine is released`() {
        val engine = FakeEngine()
        val c = controller(engine, FakeFocus(grant = false))
        c.start(null)
        assertFalse(c.state.value.active)
        assertTrue(engine.closed)
        assertEquals(ReadAloudController.FOCUS_REFUSED, c.state.value.message)
    }

    @Test fun `no engine says why and starts nothing`() {
        val c = controller(result = EngineResult.Unavailable("No voice installed."))
        c.start(null)
        assertFalse(c.state.value.active)
        assertEquals("No voice installed.", c.state.value.message)
    }

    @Test fun `stop closes the engine, drops focus and clears the highlight`() {
        val engine = FakeEngine()
        val focus = FakeFocus()
        val c = controller(engine, focus)
        c.start(null)
        engine.utterance.value = "{sentence}"
        assertEquals("{sentence}", c.state.value.utteranceJson)
        c.stop()
        assertTrue(engine.closed)
        assertEquals(1, focus.abandoned)
        assertFalse(c.state.value.active)
        assertNull(c.state.value.utteranceJson)
    }

    @Test fun `reaching the end of the book stops and says so`() {
        val engine = FakeEngine()
        val c = controller(engine)
        c.start(null)
        engine.status.value = EngineStatus.Ended
        assertFalse(c.state.value.active)
        assertEquals("Reached the end of the book.", c.state.value.message)
    }

    @Test fun `an engine failure stops and shows its own sentence`() {
        val engine = FakeEngine()
        val c = controller(engine)
        c.start(null)
        engine.status.value = EngineStatus.Failed("The voice is not installed.")
        assertFalse(c.state.value.active)
        assertEquals("The voice is not installed.", c.state.value.message)
    }

    @Test fun `speed cycles through the steps and reaches the engine`() {
        val engine = FakeEngine()
        val c = controller(engine)
        c.start(null)
        c.cycleSpeed()
        assertEquals(1.25f, engine.appliedSpeed, 0f)
        assertEquals("1.25x", c.state.value.speedLabel)
        repeat(ReadAloudController.SPEEDS.size - 1) { c.cycleSpeed() }
        assertEquals(1.0f, engine.appliedSpeed, 0f)
        assertEquals("1.0x", c.state.value.speedLabel)
    }

    @Test fun `a chosen speed is applied to a session started later`() {
        val engine = FakeEngine()
        val c = controller(engine)
        c.cycleSpeed()
        c.start(null)
        assertEquals(1.25f, engine.appliedSpeed, 0f)
    }

    @Test fun `speed labels read as multipliers`() {
        assertEquals("2.0x", ReadAloudUi(speed = 2.0f).speedLabel)
        assertEquals("0.75x", ReadAloudUi(speed = 0.75f).speedLabel)
        assertEquals(listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f), ReadAloudController.SPEEDS)
    }
}
