package com.kevin.legion.look

import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.service.LiveNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ADR 0084's controller: both doors, the throttle, the outcome-verb rule for sight, and that frames
 * stop at close. Robolectric for the Context and org.json. No camera and no socket: the [LookController.Sink]
 * is a fake standing in for `LiveSessionController`.
 */
@RunWith(RobolectricTestRunner::class)
class LookControllerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private class FakeSink(var conversation: Boolean = true, var accept: Boolean = true) : LookController.Sink {
        val frames = mutableListOf<ByteArray>()
        var handStarts = 0
        override fun inConversation() = conversation
        override fun sendFrame(jpeg: ByteArray): Boolean {
            if (!accept) return false
            frames += jpeg
            return true
        }
        override fun beginByHand(): String? {
            handStarts++
            return null
        }
    }

    private class FakeNotes : LiveNotes.Sink {
        val offered = mutableListOf<Pair<String, String>>()
        val withdrawn = mutableListOf<String>()
        override fun offer(key: String, text: String): Boolean {
            offered += key to text
            return true
        }
        override fun withdraw(key: String) {
            withdrawn += key
        }
    }

    private var now = 10_000L
    private val sink = FakeSink()
    private val notes = FakeNotes()
    private var launches = 0

    @Before
    fun setUp() {
        LookController.resetForTest()
        LookController.register(sink)
        LookController.clock = { now }
        LookController.motionProbe = { LookRules.Motion.STILL }
        LookController.mainDispatcher = Dispatchers.Unconfined
        LookController.firstFrameWaitMs = 200L
        LookController.launcher = { _, _ ->
            launches++
            true
        }
        LiveNotes.register(notes)
    }

    @After
    fun tearDown() {
        LiveNotes.unregister(notes)
        LookController.resetForTest()
    }

    private val jpeg = byteArrayOf(1, 2, 3)

    @Test
    fun `no frame goes anywhere while no look is open`() {
        assertFalse(LookController.frameWanted())
        assertFalse(LookController.offerFrame(jpeg))
        assertTrue(sink.frames.isEmpty())
    }

    @Test
    fun `an open look sends about one frame a second, and only into an open conversation`() {
        LookController.ensureOpen(LookController.Source.HAND)
        assertTrue(LookController.offerFrame(jpeg))
        now += 400
        assertFalse(LookController.frameWanted())
        assertFalse(LookController.offerFrame(jpeg))
        now += 600
        assertTrue(LookController.offerFrame(jpeg))
        assertEquals(2, sink.frames.size)
        assertEquals(2, LookController.state.value.framesSent)

        now += 1_000
        sink.conversation = false
        assertFalse(LookController.offerFrame(jpeg))
        assertEquals(2, sink.frames.size)
    }

    @Test
    fun `a frame the socket refused is not counted as seen`() {
        LookController.ensureOpen(LookController.Source.HAND)
        sink.accept = false
        assertFalse(LookController.offerFrame(jpeg))
        assertEquals(0, LookController.state.value.framesSent)
    }

    @Test
    fun `close stops frames at once and tells the model it can no longer see`() {
        LookController.ensureOpen(LookController.Source.HAND)
        assertTrue(LookController.offerFrame(jpeg))
        LookController.close()
        now += 5_000
        assertFalse(LookController.offerFrame(jpeg))
        assertEquals(1, sink.frames.size)
        assertFalse(LookController.state.value.open)
        assertEquals(listOf(LookController.CLOSED_KEY to LookRules.CLOSED_NOTE), notes.offered)
    }

    @Test
    fun `closing a look that never sent a frame says nothing to the model`() {
        LookController.ensureOpen(LookController.Source.HAND)
        LookController.close("why")
        assertTrue(notes.offered.isEmpty())
        assertEquals("why", LookController.state.value.notice)
    }

    @Test
    fun `voice look refuses in words with no conversation, and opens nothing`() = runBlocking {
        sink.conversation = false
        val result = LookController.openForVoice(context)
        assertFalse(result.getBoolean("success"))
        assertEquals(LookRules.REFUSED_NO_CONVERSATION, result.getString("message"))
        assertEquals(0, launches)
    }

    @Test
    fun `voice look refuses while moving, and opens nothing`() = runBlocking {
        LookController.motionProbe = { LookRules.Motion.MOVING }
        val result = LookController.openForVoice(context)
        assertFalse(result.getBoolean("success"))
        assertEquals(LookRules.REFUSED_MOVING, result.getString("message"))
        assertEquals(0, launches)
        assertFalse(LookController.state.value.open)
    }

    @Test
    fun `voice look claims sight only after a frame went out`() = runBlocking {
        LookController.launcher = { _, _ ->
            launches++
            LookController.offerFrame(jpeg)
            true
        }
        val result = LookController.openForVoice(context)
        assertTrue(result.getBoolean("success"))
        assertEquals(LookRules.SEEING, result.getString("message"))
        assertEquals(LookRules.RULES, result.getString("instruction"))
    }

    @Test
    fun `voice look with no frame in time says nothing was seen, then tells the model at the first`() = runBlocking {
        LookController.launcher = { _, source ->
            launches++
            LookController.ensureOpen(source) // the screen came up
            true
        }
        val result = LookController.openForVoice(context)
        assertFalse(result.getBoolean("success"))
        assertEquals(LookRules.NOT_YET, result.getString("message"))
        assertTrue(LookController.state.value.open)
        assertTrue(LookController.offerFrame(jpeg))
        now += 1_000
        assertTrue(LookController.offerFrame(jpeg))
        assertEquals(listOf(LookController.LIVE_KEY to LookRules.LIVE_LATE_NOTE), notes.offered)
    }

    @Test
    fun `voice look whose screen never came up says so, not that it is open`() = runBlocking {
        val result = LookController.openForVoice(context)
        assertFalse(result.getBoolean("success"))
        assertEquals(LookRules.NOT_SHOWN, result.getString("message"))
    }

    @Test
    fun `a cancelled look result still gets the rules to the model, now or at the first frame`() {
        LookController.ensureOpen(LookController.Source.VOICE)
        LookController.resultDropped()
        assertTrue(notes.offered.isEmpty())
        assertTrue(LookController.offerFrame(jpeg))
        assertEquals(listOf(LookController.LIVE_KEY to LookRules.LIVE_LATE_NOTE), notes.offered)

        notes.offered.clear()
        LookController.resultDropped()
        assertEquals(listOf(LookController.LIVE_KEY to LookRules.LIVE_LATE_NOTE), notes.offered)
    }

    @Test
    fun `hand look refuses while moving and opens nothing`() = runBlocking {
        LookController.motionProbe = { LookRules.Motion.MOVING }
        assertEquals(LookRules.REFUSED_MOVING, LookController.openByHand(context))
        assertEquals(0, launches)
    }

    @Test
    fun `hand look opens the screen, and the conversation starts through the same sink`() = runBlocking {
        assertNull(LookController.openByHand(context))
        assertEquals(1, launches)
        assertTrue(LookController.state.value.open)
        assertEquals(LookController.Source.HAND, LookController.state.value.source)
        LookController.startConversationForHand()
        assertEquals(1, sink.handStarts)
        assertNull(LookController.state.value.notice)
    }

    @Test
    fun `with voice not running the hand path says so in words`() {
        LookController.unregister(sink)
        LookController.ensureOpen(LookController.Source.HAND)
        LookController.startConversationForHand()
        assertEquals(LookController.VOICE_NOT_RUNNING, LookController.state.value.notice)
    }
}
