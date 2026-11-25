package com.kevin.legion.service.cascade

import com.kevin.legion.testutil.MainSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import org.junit.Test

/**
 * How the carry is wired into the conversation, read from source: the session needs a live Context,
 * a mic and Room to construct, the limit [CascadeWiringTest] states. What the rules themselves do
 * is [RewakeCarryTest]; this pins that the session hands them the truth and prepends the turns in
 * one place only.
 */
class RewakeCarryWiringTest {

    @Test
    fun `the holder outlives a conversation: the controller owns it and hands it to each one`() {
        val controller = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())
        assertTrue("one holder for the device", controller.contains("private val rewakeCarry = RewakeCarry()"))
        assertEquals(
            "constructed once, never per conversation",
            1,
            Regex("RewakeCarry\\(\\)").findAll(controller).count(),
        )
        assertTrue("every conversation gets it", controller.contains("rewake = rewakeCarry,"))
        // The session never makes one of its own, which would die with the conversation.
        assertFalse(session().contains("RewakeCarry()"))
    }

    @Test
    fun `the last conversation's turns are taken once, at open, and never for a test conversation`() {
        val s = session()
        assertTrue(
            s.contains("if (test == null) rewake.take(System.currentTimeMillis()) else emptyList()"),
        )
        assertEquals("taken in one place", 1, Regex("rewake\\.take\\(").findAll(s).count())
    }

    @Test
    fun `carried turns ride his turns' prompt and are never seeded into history`() {
        val s = session()
        assertTrue(
            "his turns and their drafts",
            s.contains("CascadePrompt.contents(carried + history, heard, turnPreamble())"),
        )
        // The greeting round is the model's hello, not a turn of his: it sees this conversation only.
        assertTrue(s.contains("CascadePrompt.contents(history, heard, preamble)"))
        // Nothing adds a carried turn to history, so history.isEmpty() and history.size still mean
        // "this conversation" to the greeting rule and the speculative draft's context check.
        assertFalse(s.contains("history.addAll(carried"))
        assertFalse(s.contains("history += carried"))
        assertEquals("one prepend site", 1, Regex("carried \\+ history").findAll(s).count())
    }

    @Test
    fun `the close hands over the real taint, from the one funnel every close goes through`() {
        val shutdown = bodyOf(session(), "shutdown")
        assertTrue("every close leaves its decision behind", shutdown.contains("rewake.ended("))
        // Not a constant: the write guard would be bypassed by a carried turn if this read `false`.
        assertTrue("the session's own read-through state", shutdown.contains("outsideTextRead = outsideTextRead,"))
        assertTrue(shutdown.contains("test = test != null,"))
        assertTrue(shutdown.contains("turns = history.toList(),"))
        assertTrue(shutdown.contains("reason = reason,"))
        assertEquals("one close door", 1, Regex("rewake\\.ended\\(").findAll(session()).count())
    }

    @Test
    fun `every way outside text enters a conversation marks it, so it leaves nothing behind`() {
        val s = session()
        // A read-through opener's prompt and the ADR 0090 one-turn carry: both set startMarked.
        assertTrue(bodyOf(s, "answerTurn").contains("if (record.startMarked) outsideTextRead = true"))
        // A read-through tool, at the one door history grows through.
        assertTrue(bodyOf(s, "recordTurn").contains("if (readThrough) outsideTextRead = true"))
        assertEquals("set in those two places only", 2, Regex("outsideTextRead = true").findAll(s).count())
        // Once a conversation has read outside text nothing takes the mark back off it.
        assertEquals(
            "the field's own default, and nothing else assigns false",
            1,
            Regex("outsideTextRead = false").findAll(s).count(),
        )
    }

    @Test
    fun `the chime and the carry share one definition of the end of his conversation`() {
        val cue = stripComments(File(sourceRoot(), "service/cascade/CloseCue.kt").readText())
        assertTrue(cue.contains("fun endsHisConversation(reason: String): Boolean = reason in PLAYS"))
        // One list: `decide` reads the predicate too, so the two can never drift apart.
        assertEquals("PLAYS is read through the predicate alone", 1, Regex("in PLAYS").findAll(cue).count())
        val carry = stripComments(File(sourceRoot(), "service/cascade/RewakeCarry.kt").readText())
        assertTrue(carry.contains("CloseCue.endsHisConversation(reason)"))
    }

    // --- source access ----------------------------------------------------------------------------

    private fun session() = stripComments(MainSource.read(MainSource.CASCADE_SESSION))

    private fun bodyOf(source: String, name: String): String {
        val signature = Regex("fun $name\\(").find(source)?.range?.first ?: fail("no fun $name(").let { -1 }
        val open = source.indexOf('{', signature)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        fail("unbalanced braces reading $name")
        return ""
    }

    private fun sourceRoot(): File = MainSource.root()

    private fun stripComments(text: String): String =
        LINE_COMMENT.replace(BLOCK_COMMENT.replace(text) { "\n".repeat(it.value.count { c -> c == '\n' }) }, "")

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("(?<![:\"])//[^\n]*")
    }
}
