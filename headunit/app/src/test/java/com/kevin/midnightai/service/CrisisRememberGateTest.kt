package com.kevin.midnightai.service

import com.kevin.midnightai.ai.CrisisMemoryGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ticket #462 on the head unit: `remember` saves nothing once the detector has fired on this
 * socket, and it says so instead of acknowledging a save.
 *
 * This is the hole #458 left (its review's finding 4). #458 gated the raw `episodic_turns` buffer
 * and its distillation into `companion_memories`; `remember` writes the legacy explicit `memories`
 * table through `AriaBrain` and had no gate at all. Unlike a captured turn it arrives DURING the
 * crisis turn, because a detection does not stop the model generating on the wire.
 *
 * The decision itself is a pure function. The wiring is read from source, because the session and
 * the controller both need a live Context, a socket and a mic to construct, which is the same
 * limit [RewakeCarryWiringTest] accepted; those tests say so rather than claiming behaviour.
 */
class CrisisRememberGateTest {

    // --- the decision -------------------------------------------------------------------------

    @Test
    fun `remember saves a memory, so a crisis socket refuses it`() {
        assertTrue(CrisisMemoryGate.savesAMemory("remember"))
    }

    @Test
    fun `recalling and the car tools are not memory saves`() {
        // Reading old memories back is not saving this exchange, and refusing the car's own tools
        // would be a wider rule than the ticket, with no safety it buys.
        assertFalse(CrisisMemoryGate.savesAMemory("recall_memory"))
        assertFalse(CrisisMemoryGate.savesAMemory("get_health"))
        assertFalse(CrisisMemoryGate.savesAMemory("control_music"))
        assertFalse(CrisisMemoryGate.savesAMemory("log_service"))
        assertFalse(CrisisMemoryGate.savesAMemory("navigate_to"))
    }

    @Test
    fun `the refusal says nothing was saved and offers nothing`() {
        val words = CrisisMemoryGate.REFUSAL
        assertTrue(words.contains("Nothing was saved"))
        assertTrue(words.contains("do not offer to save it"))
        // No promise, because this holds for the whole socket, and no words of his repeated back.
        assertFalse(words.lowercase().contains("ask again"))
        assertFalse(words.lowercase().contains("next time"))
    }

    // --- the wiring, read from source ---------------------------------------------------------

    @Test
    fun `the socket latches the crisis at detection and clears it only on a fresh socket`() {
        val s = session()
        assertTrue(
            "checkForCrisis sets the socket latch",
            bodyOf(s, "checkForCrisis").contains("crisisOnThisSocket = true"),
        )
        assertEquals(
            "set in one place only",
            1,
            Regex("crisisOnThisSocket = true").findAll(s).count(),
        )
        assertEquals(
            "cleared in one place only",
            1,
            Regex("crisisOnThisSocket = false").findAll(s).count(),
        )
        assertTrue(
            "and that place is start(), a fresh socket",
            bodyOf(s, "start").contains("crisisOnThisSocket = false"),
        )
        // The whole point: unlike crisisFiredThisTurn it is NOT reset when the turn ends, because
        // the model is still generating and a `remember` can arrive after the phrase.
        assertFalse(
            "never reset beside the per-turn flag",
            s.lines().any { it.contains("crisisFiredThisTurn = false") && it.contains("crisisOnThisSocket") },
        )
    }

    @Test
    fun `the detector runs before the latch is read, so a call read after the phrase sees it`() {
        // Both live on the socket reader: handleServerContent appends the transcript and calls
        // checkForCrisis, so the latch is down before any later frame of the same socket is read.
        assertTrue(bodyOf(session(), "handleServerContent").contains("checkForCrisis()"))
    }

    @Test
    fun `the controller refuses a memory save before the tool runs`() {
        val body = bodyOf(controller(), "handleToolCall")
        assertTrue(
            "the gate reads the socket's latch",
            body.contains("CrisisMemoryGate.savesAMemory(call.name) && s.crisisOnThisSocket"),
        )
        assertTrue("and refuses in the gate's own words", body.contains("CrisisMemoryGate.REFUSAL"))
        // The refusal must stand in for the response, so LiveToolbox.dispatch never runs for it.
        assertTrue(
            "the refusal short-circuits the dispatch",
            body.contains("crisisRefusal ?: try"),
        )
        val gate = body.indexOf("crisisRefusal")
        val dispatch = body.indexOf("LiveToolbox.dispatch")
        assertTrue("the gate comes first", gate in 0 until dispatch)
    }

    // --- source access ------------------------------------------------------------------------

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
