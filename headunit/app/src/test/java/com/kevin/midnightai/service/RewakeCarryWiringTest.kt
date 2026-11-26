package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import org.junit.Test

/**
 * How the carry is wired into Moose's conversation, read from source: the session and the controller
 * both need a live Context, a socket and a mic to construct. What the rules do is [RewakeCarryTest];
 * this pins that the session feeds them at every end and the controller takes the block once, on the
 * one cold start.
 */
class RewakeCarryWiringTest {

    @Test
    fun `the holder outlives a socket - the controller owns it and hands it to both it makes`() {
        val c = controller()
        assertTrue("one holder for the unit", c.contains("private val rewakeCarry = RewakeCarry()"))
        assertEquals("constructed once", 1, Regex("RewakeCarry\\(\\)").findAll(c).count())
        assertEquals(
            "the conversation socket and the proactive one",
            2,
            Regex("GeminiLiveSession\\(appContext, rewakeCarry\\)").findAll(c).count(),
        )
        assertFalse("the session never makes its own", session().contains("RewakeCarry()"))
    }

    @Test
    fun `a completed exchange of a conversation is held, and a speak-only line is not`() {
        val s = session()
        assertTrue(
            "beside the episodic row, which already keeps the same pair",
            s.contains("if (vadMode && keepWarm) carryTurn(heard, spoken)"),
        )
        assertEquals("one place turns enter", 1, Regex("holder\\.turn\\(heard, spoken\\)").findAll(s).count())
    }

    /**
     * The crisis guard. `silentDestroy` sets `closed` and only then calls `abandoned()`, and it closes
     * the socket gracefully, so frames keep arriving through the handshake. Without a check under the
     * holder's own lock, a `turnComplete` delivered in that window puts the distress exchange back
     * after the controller wiped it, with no end stamp, and a later cold conversation replays it.
     */
    @Test
    fun `a turn arriving after the session closed is refused, so a wipe is final`() {
        val s = session()
        val carry = bodyOf(s, "carryTurn")
        assertTrue("the holder's own lock, the one abandoned() waits behind", carry.contains("synchronized(holder)"))
        assertTrue("nothing is recorded once the session is closed", carry.contains("if (!closed.get()) holder.turn(heard, spoken)"))
        assertEquals("one guarded door into the holder's turns", 1, Regex("\\.turn\\(heard, spoken\\)").findAll(s).count())
    }

    @Test
    fun `a park and his tap end the conversation, and every other teardown abandons it`() {
        val s = session()
        assertTrue("it went quiet or he said goodbye", bodyOf(s, "parkWarm").contains("rewake?.ended("))
        val close = bodyOf(s, "closeSession")
        assertTrue(close.contains("if (RewakeCarry.endsHisConversation(reason))"))
        assertTrue("his tap", close.contains("rewake?.ended("))
        assertTrue("a drop, an error, the hold timer, the server", close.contains("rewake?.abandoned()"))
        // A socket the controller replaces is not an end he made either.
        assertTrue(bodyOf(s, "silentDestroy").contains("rewake?.abandoned()"))
        assertEquals("both ends and nothing else", 2, Regex("rewake\\?\\.ended\\(").findAll(s).count())
    }

    /**
     * `abandoned()` can only mean "a conversation that never finished leaves nothing" if something
     * tells the holder a conversation is running again. `beginConversation` is both the cold start's
     * and the warm resume's one door, so the reset goes there.
     */
    @Test
    fun `a conversation starting or resuming on the socket drops the end stamp`() {
        val s = session()
        val begin = bodyOf(s, "beginConversation")
        assertTrue("the one door a cold start and a warm resume share", begin.contains("rewake?.running()"))
        assertTrue(
            "beside the flag that says a conversation is live",
            begin.contains("conversationActive = true"),
        )
        assertEquals("one reset site", 1, Regex("rewake\\?\\.running\\(\\)").findAll(s).count())
    }

    /**
     * The wipe goes AFTER the teardown, and the order is the whole guarantee.
     *
     * It used to go before, so that no later event could re-stamp the holder. [carryTurn]'s lock
     * changed that premise: once `silentDestroy` has set `closed`, nothing can be admitted at all.
     * What the old order could NOT stop was a pair admitted in the instructions between the wipe and
     * the destroy, which this handler's own `parkWarm` then stamped and `abandoned()` kept. Destroy
     * first and the admission door is already shut; wipe last and anything admitted or stamped
     * before it goes with it, stamp or no stamp. The phone's controller already reads in this order.
     */
    @Test
    fun `a crisis tears the socket down first and wipes the carry after, so nothing survives`() {
        val c = controller()
        val crisis = c.substring(c.indexOf("is LiveEvent.CrisisDetected ->"))
        assertTrue(crisis.contains("rewakeCarry.wipe()"))
        assertTrue("the teardown is still here", crisis.contains("session?.silentDestroy()"))
        assertTrue(
            "after the socket goes, so the wipe is the last word on this conversation",
            crisis.indexOf("session?.silentDestroy()") < crisis.indexOf("rewakeCarry.wipe()"),
        )
        assertEquals("one wipe site", 1, Regex("rewakeCarry\\.wipe\\(\\)").findAll(c).count())
        // Never inside a `session?.let` or an `if`: a socket that was already gone must not cost
        // the wipe. Pinned by its indent, which is the teardown's `finally` (ticket #458); these
        // files are CRLF, so the newline after the call is matched loosely rather than as a bare \n.
        assertTrue(
            "the wipe is unconditional, at the finally's own indent",
            Regex("\\n {20}rewakeCarry\\.wipe\\(\\)\\s*?\\n").containsMatchIn(crisis),
        )
    }

    /**
     * A throw inside the teardown must not cost the forgetting (ticket #458).
     *
     * The crisis branch runs inside the session's own `main` scope, whose fault guard swallows the
     * exception into a close that no-ops because `closed` is already set. So a `silentDestroy` that
     * threw used to leave the carry and the conversation's raw turns exactly where they were, which
     * is the one case where this branch failing silently is a privacy failure rather than a cosmetic
     * one.
     */
    @Test
    fun `a teardown that throws still wipes the carry and forgets the conversation`() {
        val c = controller()
        val crisis = c.substring(c.indexOf("is LiveEvent.CrisisDetected ->"))
        assertTrue(
            "the teardown is the try, so a throw in it skips neither line below",
            Regex("try \\{\\s*session\\?\\.silentDestroy\\(\\)\\s*\\} finally \\{").containsMatchIn(crisis),
        )
        assertTrue(
            "the carry and the conversation's raw turns both go in the finally",
            Regex("\\} finally \\{[^}]*rewakeCarry\\.wipe\\(\\)[^}]*forgetCrisisConversation\\(")
                .containsMatchIn(crisis),
        )
    }

    /**
     * The long replay, the one the carry's minute does not cover: a turn written to `episodic_turns`
     * is distilled into `companion_memories` and pulled back into a prompt on demand for the life of
     * the car (ticket #458). The phone records no crisis turn at all; this is how the head unit
     * matches it. The same teardown owns both windows, which is why the memory side is pinned here
     * beside the carry's.
     */
    @Test
    fun `a crisis turn never reaches the episodic buffer, and the crisis forgets the rest`() {
        val capture = bodyOf(session(), "captureEpisodicTurn")
        assertTrue(
            "decided from the completed text, not the mid-turn flag that turnComplete already reset",
            capture.contains("if (!CrisisMemoryGate.recordsTurn(driverText, closed.get())) return"),
        )
        assertTrue("before the write, not inside it", capture.indexOf("recordsTurn") < capture.indexOf("io.launch"))

        val forget = bodyOf(controller(), "forgetCrisisConversation")
        assertTrue("the whole conversation, not one turn", forget.contains("deleteSession(episodicSessionId)"))
        assertTrue(
            "on the controller's scope: silentDestroy cancels the session's own io",
            forget.contains("scope.launch(Dispatchers.IO)"),
        )

        // The read side, for rows a unit recorded before the write gate shipped: consolidation
        // drops such a session instead of distilling it into a durable memory.
        val consolidate = bodyOf(stripComments(read("ai/MemoryConsolidator.kt")), "consolidatePending")
        assertTrue(consolidate.contains("if (CrisisMemoryGate.forgetsSession(turns)) {"))
        assertTrue(
            "dropped, never handed to the model",
            consolidate.indexOf("forgetsSession") < consolidate.indexOf("distill(turns)"),
        )
    }

    @Test
    fun `the clock is monotonic, because the unit's wall clock is not trusted`() {
        val s = session()
        assertEquals(2, Regex("rewake\\?\\.ended\\(SystemClock\\.elapsedRealtime\\(\\)\\)").findAll(s).count())
        assertTrue(controller().contains("rewakeCarry.take(SystemClock.elapsedRealtime())"))
    }

    @Test
    fun `the block is taken once, on the cold start, and rides the greeting prompt`() {
        val c = controller()
        val cold = bodyOf(c, "startConversation")
        assertTrue("taken where the live context already rides", cold.contains("rewakeCarry.take("))
        assertTrue(
            "never on the very first session, which has no conversation behind it",
            cold.contains("if (isFirst) null"),
        )
        assertTrue("it reaches the greeting", cold.contains("carried,"))
        assertEquals("one take, so a warm resume takes none", 1, Regex("rewakeCarry\\.take\\(").findAll(c).count())
        // A warm resume still has Gemini's own context on the same socket and needs no replay.
        assertFalse(bodyOf(c, "resumeWarm").contains("rewakeCarry"))
    }

    // --- source access ----------------------------------------------------------------------------

    private fun session() = stripComments(read("service/GeminiLiveSession.kt"))

    private fun controller() = stripComments(read("service/LiveSessionController.kt"))

    private fun read(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("app/src/main/java/com/kevin/midnightai", "headunit/app/src/main/java/com/kevin/midnightai")
                .map { File(dir, "$it/$path") }
                .firstOrNull { it.isFile }
                ?.let { return it.readText() }
            dir = dir.parentFile
        }
        fail("could not find $path; a source test must not silently pass")
        return ""
    }

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

    private fun stripComments(text: String): String =
        LINE_COMMENT.replace(BLOCK_COMMENT.replace(text) { "\n".repeat(it.value.count { c -> c == '\n' }) }, "")

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("(?<![:\"])//[^\n]*")
    }
}
