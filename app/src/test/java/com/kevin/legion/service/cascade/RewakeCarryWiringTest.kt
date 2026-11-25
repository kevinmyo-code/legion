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
    fun `the holder outlives a conversation - the controller owns it and hands it to each one`() {
        val controller = controller()
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
    fun `only a conversation he opened to talk takes the context, or leaves any`() {
        val s = session()
        // A line Alfred opens (a proactive raise, Brief me, Look), a ring window the mic only
        // listens through, and a test conversation each take nothing and leave what is held alone,
        // so a line he never answered cannot spend or wipe the context his real re-wake wants.
        assertTrue(
            s.contains("test == null && opener == null && micClaimant == MicArbiter.Claimant.LIVE_TURN"),
        )
        assertTrue(s.contains("if (ownConversation) rewake.take(SystemClock.elapsedRealtime()) else emptyList()"))
        assertEquals("taken in one place", 1, Regex("rewake\\.take\\(").findAll(s).count())
        assertTrue(
            "and left in one place, under the same rule",
            bodyOf(s, "shutdown").contains("if (ownConversation) {"),
        )
    }

    /**
     * His "new chat" tap, in the case that matters. `cascade?.close("new conversation")` reaches no
     * session once a conversation has ended (`onClosed` nulls `cascade`), which is exactly when the
     * holder has something in it, so the wipe must not hang off that close or any other condition.
     * It sits with the panel and recorder clears, which are unconditional for the same reason.
     */
    @Test
    fun `his new chat tap wipes the holder even with no conversation open`() {
        val body = bodyOf(controller(), "newConversation")
        assertTrue("the tap reaches the holder itself", body.contains("rewakeCarry.wipe()"))
        val wipeAt = body.indexOf("rewakeCarry.wipe()")
        val closeAt = body.indexOf("cascade?.close(\"new conversation\")")
        assertTrue("the close is still there for an open conversation", closeAt > 0)
        assertTrue("and the wipe does not depend on it", wipeAt < closeAt)
        // Nothing may put the wipe behind a condition: the statement before it is another
        // unconditional clear, and no `if` opens between the body's start and the wipe.
        assertFalse("the wipe is unconditional", body.substring(0, wipeAt).contains("if ("))
        assertTrue(body.substring(0, wipeAt).contains("TypedTurnRecorder.newConversation()"))
    }

    /**
     * A crisis teardown carries nothing forward (CLAUDE.md sec 9.1). The controller's one crisis
     * handler is the place for it: "crisis" is not in [CloseCue.PLAYS], so a conversation he opened
     * leaves nothing through its own close, but a ring window, a line Alfred opened and the typed
     * panel never reach that close at all.
     */
    @Test
    fun `a crisis teardown wipes the holder`() {
        val controller = controller()
        val crisis = controller.substring(controller.indexOf("is LiveEvent.CrisisDetected ->"))
        assertTrue("before the next conversation can take anything", crisis.contains("rewakeCarry.wipe()"))
        // With the rest of ticket 02's "the next chat starts clean", not behind any condition.
        val wipeAt = crisis.indexOf("rewakeCarry.wipe()")
        assertTrue(wipeAt > crisis.indexOf("sessionResumeHandle = null"))
        assertTrue(wipeAt < crisis.indexOf("AssistantChatStore.update"))
        assertEquals(
            "wiped in the two places that mean forget, and nowhere else",
            2,
            Regex("rewakeCarry\\.wipe\\(\\)").findAll(controller).count(),
        )
    }

    @Test
    fun `the window is measured on a monotonic clock, not a wall clock that jumps`() {
        val s = session()
        assertTrue(s.contains("rewake.take(SystemClock.elapsedRealtime())"))
        assertTrue(bodyOf(s, "shutdown").contains("nowMs = SystemClock.elapsedRealtime(),"))
        assertFalse(bodyOf(s, "shutdown").contains("nowMs = System.currentTimeMillis()"))
    }

    @Test
    fun `carried turns ride his turns' prompt and are never seeded into history`() {
        val s = session()
        assertTrue(
            "his turns and their drafts",
            s.contains("CascadePrompt.contents(carriedBlock + history, heard, turnPreamble())"),
        )
        // The note is the prompt's own, never part of what this conversation leaves behind, so a
        // second re-wake cannot stack notes.
        assertTrue(s.contains("carried + ChatTurn(fromUser = false, text = RewakeCarry.NOTE)"))
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
        // The interrupted turn's own mark counts too: a close can land mid-turn, after a
        // read-through tool ran but before the turn reached history.
        assertTrue(
            "the session's own read-through state",
            shutdown.contains("outsideTextRead = outsideTextRead || turn?.readThrough == true,"),
        )
        assertTrue(shutdown.contains("test = test != null,"))
        // What was in front of the model, so a second follow-up still reaches the conversation
        // that started it: what this one carried in, then its own turns.
        assertTrue(shutdown.contains("turns = carried + history,"))
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
        // One list, two readers: the chime's own `decide`, and the carry below.
        assertTrue("the list is the shared definition", cue.contains("val PLAYS = setOf("))
        assertEquals("the chime reads it once", 1, Regex("reason !in PLAYS").findAll(cue).count())
        val carry = stripComments(File(sourceRoot(), "service/cascade/RewakeCarry.kt").readText())
        assertTrue("and the carry reads the same list", carry.contains("reason in CloseCue.PLAYS"))
        // The carry keeps no list of its own for THAT question. Its one set answers a different one:
        // which close means he asked to start fresh, which the chime has no opinion about.
        assertEquals("one set, and it is the wipes", 1, Regex("setOf\\(").findAll(carry).count())
        assertTrue(carry.contains("val WIPES = setOf(\"new conversation\")"))
    }

    // --- source access ----------------------------------------------------------------------------

    private fun session() = stripComments(MainSource.read(MainSource.CASCADE_SESSION))

    private fun controller() = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())

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
