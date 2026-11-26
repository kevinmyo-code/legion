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

    @Test
    fun `a crisis teardown wipes the carry, so nothing of it is replayed`() {
        val c = controller()
        val crisis = c.substring(c.indexOf("is LiveEvent.CrisisDetected ->"))
        assertTrue(crisis.contains("rewakeCarry.wipe()"))
        assertTrue(
            "before the socket goes, so no later event can re-stamp it",
            crisis.indexOf("rewakeCarry.wipe()") < crisis.indexOf("session?.silentDestroy()"),
        )
        assertEquals("one wipe site", 1, Regex("rewakeCarry\\.wipe\\(\\)").findAll(c).count())
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
