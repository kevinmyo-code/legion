package com.kevin.legion.service.testturn

import android.os.Looper
import com.kevin.legion.service.cascade.CascadeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The controller's half of [TestTurns.Host]: which open conversation is a test one, opening one,
 * feeding it, and closing it (by the computer, by the watchdog, or when real use begins). Every
 * call from the adb door runs on the controller's main thread, where the controller keeps its state.
 *
 * `LiveSessionController` hands it four doors: [openTest] (open a test conversation, or say why
 * not), [current] (the open cascade conversation), [close] (close one) and [phase] (the strip's
 * phase, for status).
 */
internal class TestTurnDriver(
    private val scope: CoroutineScope,
    private val openTest: (TestSession) -> String?,
    private val current: () -> CascadeSession?,
    private val close: (CascadeSession, String) -> Unit,
    private val phase: () -> String,
) : TestTurns.Host {
    /** The test conversation and its session; both null when none is open. */
    private var test: TestSession? = null
    private var testCascade: CascadeSession? = null
    private var watchdog: Job? = null

    /** True when [c] is the open test conversation. */
    fun owns(c: CascadeSession?): Boolean = c != null && c === testCascade

    /** The session a conversation being opened should carry (set just before [openTest] opens it). */
    fun opened(c: CascadeSession) {
        if (test != null && testCascade == null) testCascade = c
    }

    /** The controller saw [c] close: test mode ends with it. */
    fun closed(c: CascadeSession?) {
        if (c != null && c !== testCascade) return
        if (test != null) TestTurns.log("conversation_closed id=${test?.id}")
        test = null
        testCascade = null
        watchdog?.cancel()
        watchdog = null
    }

    override fun status(): Map<String, Any> = onMain(emptyMap()) {
        val c = current()?.takeIf { it.isOpen }
        val conversation = when {
            c == null -> "none"
            owns(c) -> "test"
            else -> "real"
        }
        mapOf(
            "conversation" to conversation,
            "test_id" to (test?.id ?: ""),
            "speaking" to (owns(c) && c!!.testSpeaking),
            "busy" to (owns(c) && c!!.testBusy),
            "turns_answered" to (if (owns(c)) c!!.answeredTurns else 0),
            "user_turns" to (if (owns(c)) c!!.heardTurns else 0),
            "audio_pending" to (owns(c) && c!!.testAudioPending),
            "phase" to phase(),
        )
    }

    override fun turn(id: String, text: String, allowWrites: Boolean): String? = onMain("timeout") {
        val c = ensureOpen(id, allowWrites) ?: return@onMain lastRefusal
        TestTurns.log("turn id=$id writes=$allowWrites chars=${text.length}")
        if (c.testTurn(text)) null else "not_taken"
    }

    override fun audio(id: String, pcm: ByteArray, allowWrites: Boolean, barge: Boolean): String? = onMain("timeout") {
        val c = ensureOpen(id, allowWrites) ?: return@onMain lastRefusal
        TestTurns.log("audio id=$id writes=$allowWrites barge=$barge ms=${pcm.size / BYTES_PER_MS}")
        if (c.testAudio(pcm, barge)) null else "not_taken"
    }

    override fun end(reason: String) {
        onMain(Unit) {
            val c = testCascade
            if (c != null && c.isOpen) {
                TestTurns.log("end reason=$reason id=${test?.id}")
                close(c, "test_$reason")
            } else {
                closed(c)
            }
        }
    }

    private var lastRefusal: String? = null

    /** The open test conversation, opening one if none is open. Null (and [lastRefusal]) when not. */
    @Suppress("ReturnCount") // one worded refusal per way it cannot open
    private fun ensureOpen(id: String, allowWrites: Boolean): CascadeSession? {
        val c = current()?.takeIf { it.isOpen }
        if (c != null && !owns(c)) {
            lastRefusal = "real_conversation"
            return null
        }
        if (c != null) {
            test?.touch(id, allowWrites)
            return c
        }
        val session = TestSession(id, allowWrites)
        test = session
        testCascade = null
        val why = openTest(session)
        val opened = testCascade
        if (why != null || opened == null) {
            test = null
            testCascade = null
            lastRefusal = why ?: "not_opened"
            return null
        }
        TestTurns.log("conversation_open id=$id")
        startWatchdog(session)
        return opened
    }

    private fun startWatchdog(session: TestSession) {
        watchdog?.cancel()
        watchdog = scope.launch {
            var why: String? = null
            while (test === session && why == null) {
                delay(WATCHDOG_MS)
                why = session.expired()
            }
            if (why != null && test === session) end(why)
        }
    }

    /**
     * Runs [block] on the main thread: at once when already there (the watchdog, the Settings
     * switch), else from the binder thread, bounded, and [fallback] if the controller is gone.
     */
    private fun <T> onMain(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        return runCatching {
            runBlocking { withTimeoutOrNull(MAIN_TIMEOUT_MS) { withContext(Dispatchers.Main) { block() } } }
        }.getOrNull() ?: fallback
    }

    private companion object {
        const val WATCHDOG_MS = 5_000L
        const val MAIN_TIMEOUT_MS = 3_000L
        const val BYTES_PER_MS = 32
    }
}
