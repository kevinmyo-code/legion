package com.kevin.legion.service.cascade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How Alfred picks the line he answers a call with: never the same one twice in a row (across a fresh
 * read of the saved state too), never the assistant's name hardcoded, and a renamed persona offered
 * only the lines that are true under its name unless their audio is in hand.
 */
@RunWith(RobolectricTestRunner::class)
class CallPickupLinesTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val default = "Alfred"
    private val nothingInHand: (String) -> Boolean = { false }

    private fun pick(
        name: String = default,
        at: LocalDateTime = NOON,
        inHand: (String) -> Boolean = nothingInHand,
        random: Random = Random(7),
    ) = CallPickup.pick(ctx, name, default, inHand, at, random)

    @Test
    fun `never the same line twice in a row, over two hundred calls`() {
        val said = (1..PICKS).map { pick(random = Random(it)) }
        assertTrue("one line repeated back to back: $said", said.zipWithNext().none { it.first == it.second })
    }

    @Test
    fun `the last line said is remembered, so the next call after a restart says another`() {
        val first = pick()
        // A fresh pick reads the saved state back out of the pool's own prefs file, as a new process does.
        val second = CallPickup.pick(ctx, default, default, nothingInHand, NOON, Random(7))
        assertFalse("the saved last line was ignored", first == second)
    }

    @Test
    fun `no line says the assistant's name as a literal, and every line is filled in`() {
        for (entry in CallPickup.POOL) {
            assertFalse("${entry.id} hardcodes the name", entry.text.contains(default))
            assertEquals("${entry.id}'s named flag", entry.named, entry.text.contains("{name}"))
        }
        assertTrue(pick(name = "Jarvis", inHand = { true }).let { it.contains("Jarvis") || !it.contains("{") })
        assertTrue("nothing is left unfilled", CallPickup.warmable("Jarvis").none { it.contains("{name}") })
    }

    @Test
    fun `a renamed persona with nothing in hand is offered only the lines that say no name`() {
        val offered = CallPickup.offered("Jarvis", default, nothingInHand)
        assertTrue("a named line would be said in the wrong voice", offered.none { it.named })
        assertTrue(offered.isNotEmpty())
        for (line in 1..PICKS) {
            val said = pick(name = "Jarvis", random = Random(line))
            assertFalse("said the old name: $said", said.contains(default))
            assertTrue("said a line it has no audio for: $said", offered.any { said == it.text })
        }
    }

    @Test
    fun `a renamed persona keeps a named line whose audio is in hand`() {
        val offered = CallPickup.offered("Jarvis", default, { it.contains("Jarvis") })
        assertEquals("every line is offered again", CallPickup.POOL.size, offered.size)
    }

    /**
     * Review F6. A rename used to lose the named lines for good, not until warm: keeping a line's
     * audio at all is membership of the voice's keep set, and the static set holds the pool only
     * under the register's own name, so a renamed line was never kept and the pool stayed narrowed
     * to the three lines that say no name. The renamed pool is handed to the voice instead, and
     * warmed on the ordinary filler schedule, so one conversation restores the whole pool.
     */
    @Test
    fun `a renamed persona's lines are handed to the voice, so one render restores the pool`() {
        val renamed = CallPickup.renamedKeep("Jarvis", default)
        assertEquals("every line, under the new name", CallPickup.POOL.size, renamed.size)
        assertTrue("in the form the voice is asked for", renamed.all { it == SpokenText.forSpeech(it) })
        assertTrue("and none of them says the old name", renamed.none { it.contains(default) })
        // What the session hands the voice is exactly what the pickup then probes for.
        val inHandAfterOneRender: (String) -> Boolean = { SpokenText.forSpeech(it) in renamed }
        val offered = CallPickup.offered("Jarvis", default, inHandAfterOneRender)
        assertEquals("the full pool is offered again", CallPickup.POOL.size, offered.size)
        assertTrue("including the named ones", offered.any { it.named })
    }

    @Test
    fun `the register's own name needs nothing extra, its lines are already kept`() {
        assertTrue(CallPickup.renamedKeep(default, default).isEmpty())
        assertTrue("case does not make a rename", CallPickup.renamedKeep("alfred", default).isEmpty())
        // The static set is what holds them, so an unrenamed install pays nothing for this.
        for (line in CallPickup.warmable(default)) {
            assertTrue("$line is kept", SpokenText.forSpeech(line) in ScriptedLines.KEEP)
        }
    }

    @Test
    fun `a time-of-day line is offered in its window and never outside it`() {
        val windowed = CallPickup.POOL.filter { it.window != null }
        assertTrue("the pool has time-of-day lines", windowed.isNotEmpty())
        for (entry in windowed) {
            assertFalse("${entry.id} must not cover midday", entry.window!!.contains(NOON.toLocalTime()))
        }
    }

    private companion object {
        const val PICKS = 200
        val NOON: LocalDateTime = LocalDateTime.of(2026, 11, 24, 12, 0)
    }
}
