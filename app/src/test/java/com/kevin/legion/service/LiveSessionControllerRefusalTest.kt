package com.kevin.legion.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers the 2026-09-07 "a tap that does nothing, silently" defect.
 *
 * **What went wrong.** `live_connect_day` recorded 24 Live connects in one day, none of them
 * carrying a turn, while Kevin's own account of that day was "I couldn't connect to voice" and his
 * conclusion was that his Gemini credits had run out. They had not, and no 429 has ever been
 * recorded on this install. Every guard inside [LiveSessionController.onTap] was doing its job;
 * several of them returned in total silence, and the ones that did speak emitted into
 * [CompanionPhase.notice] while it had `replay = 0` and - on two of the three doors that reach
 * `onTap` - no collector at all.
 *
 * CLAUDE.md sec 7 forbids the assistant asserting an outcome it did not observe. This is the same
 * cost through the mirror: **the app asserted nothing, and a person reasonably concluded something
 * false.**
 *
 * Two halves, and both are needed - either alone passes for the wrong reason:
 *  - **the sentences** ([LiveSessionController.refusalNotice]), a pure function walked
 *    exhaustively below;
 *  - **the wiring**, which no JVM test can exercise directly ([LiveSessionController] needs a live
 *    Context, a [GeminiLiveSession] and Room to construct at all - the same constraint
 *    [LiveSessionControllerIdleReconnectTest] and [GeminiLiveSessionEpisodicExclusionTest] already
 *    document), so it is checked by reading the source, in the shape
 *    [com.kevin.legion.calendar.NoCalendarContractTest] established for exactly this problem.
 *
 * **What the source scan proves, and what it does not.** It proves every `return` inside `onTap` is
 * preceded by a call that either reports a refusal or hands off to a path that speaks. It does NOT
 * prove the sentence is true of the state it was raised for, and it does not prove anything was
 * ever rendered on a real phone - the strip is the renderer and no unit test draws it.
 */
class LiveSessionControllerRefusalTest {

    // --- the sentences ---------------------------------------------------

    @Test
    fun `every refusal has a sentence, and no two share one`() {
        val sentences = LiveSessionController.VoiceRefusal.entries.map {
            LiveSessionController.refusalNotice(it)
        }
        // Exhaustive by construction: `entries` walks the whole enum, so a constant added without a
        // branch cannot pass here - and, because refusalNotice has no `else`, cannot compile.
        sentences.forEach { assertTrue("a blank refusal is the defect, not the fix", it.isNotBlank()) }
        assertEquals(
            "two refusals sharing a sentence means one of them cannot be told apart on screen",
            sentences.size,
            sentences.toSet().size,
        )
    }

    @Test
    fun `every refusal names an outcome, not merely a state`() {
        // The whole correction. "On a call" describes the world; "Didn't start - you're on a call"
        // answers the question the person actually has, which is why nothing happened when they
        // asked. Every sentence therefore leads with what did NOT happen.
        LiveSessionController.VoiceRefusal.entries.forEach { refusal ->
            val sentence = LiveSessionController.refusalNotice(refusal)
            assertTrue(
                "$refusal says \"$sentence\", which names no outcome",
                sentence.startsWith("Didn't") || sentence.startsWith("Ended"),
            )
        }
    }

    @Test
    fun `every refusal gives a reason after the outcome`() {
        LiveSessionController.VoiceRefusal.entries.forEach { refusal ->
            val sentence = LiveSessionController.refusalNotice(refusal)
            assertTrue(
                "$refusal says \"$sentence\" and never says why",
                sentence.contains(" - "),
            )
        }
    }

    @Test
    fun `the missing-key refusal points at where the key is entered`() {
        // The one refusal with somewhere to go. Naming Setup is what turns it from a complaint into
        // an instruction, and a missing/exhausted key is the exact state Kevin was in when he
        // concluded the app was broken.
        assertTrue(
            LiveSessionController.refusalNotice(LiveSessionController.VoiceRefusal.NO_KEY)
                .contains("Setup"),
        )
    }

    @Test
    fun `no refusal shouts`() {
        // These land in AssistantStripResolver's label slot, beside "Tap to talk" and "Can't hear
        // you - another app has the microphone". The all-caps strings they replaced ("ON A CALL",
        // "NO SIGNAL OUT HERE") were written for the since-deleted Cruise/Lights Out screens.
        LiveSessionController.VoiceRefusal.entries.forEach { refusal ->
            val sentence = LiveSessionController.refusalNotice(refusal)
            assertFalse("$refusal still shouts: \"$sentence\"", sentence == sentence.uppercase())
        }
    }

    // --- the wiring ------------------------------------------------------

    @Test
    fun `every early return in onTap reports first`() {
        val lines = bodyOf("onTap").lines()
        var examined = 0
        lines.forEachIndexed { index, line ->
            if (!BARE_RETURN.containsMatchIn(line)) return@forEachIndexed
            examined++
            val previous = lines.take(index).lastOrNull { it.isNotBlank() }.orEmpty()
            // A `return` that shares its line with the guard must be reported ON that line;
            // only a `return` standing alone may lean on the statement above it. Without
            // that split, a silent one-liner guard passed because the NEIGHBOURING guard's
            // refuse() happened to be the previous line - found by mutating the source and
            // watching this scan stay green, which is the only way to learn that a check
            // like this is looser than it reads.
            val standalone = line.trim() == "return" || line.trim() == "return }"
            val reachable = if (standalone) line + "\n" + previous else line
            assertTrue(
                "onTap line ${index + 1} returns without telling anyone: \"${line.trim()}\". " +
                    "A tap that ends here produces no speech and no words on screen, which is the " +
                    "exact defect this test exists for - route it through refuse().",
                REPORTS_OR_HANDS_OFF.any { reachable.contains(it) },
            )
        }
        // CLAUDE.md sec 4 rule 6, applied to a test instead of to an ingest: a check that passes
        // when nothing parsed is not a check. If BARE_RETURN stops matching - a reformat, a Kotlin
        // idiom this regex does not know - every assertion in the loop above is skipped and the
        // test goes green having examined nothing at all.
        assertEquals("the return scanner matched nothing, so it proved nothing", 6, examined)
    }

    @Test
    fun `onTap refuses for exactly the reasons this test knows about`() {
        // Pins the ENUMERATION, not just the shape. A new guard added to onTap with a new
        // VoiceRefusal fails here until someone has decided, in writing, what it says - which is
        // the step that was skipped each time one of these guards was added silently.
        val used = Regex("VoiceRefusal\\.([A-Z_]+)")
            .findAll(bodyOf("onTap"))
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            setOf("ENDED_ACTIVE_CHAT", "ON_A_CALL", "NO_KEY", "NO_MIC_PERMISSION", "OFFLINE"),
            used,
        )
    }

    @Test
    fun `onTap checks the microphone permission before it opens a socket`() {
        // Added 2026-09-07, and it is a spend fix as much as an honesty one. Without it a tap with
        // RECORD_AUDIO revoked connected a socket and paid for its whole setup prompt before
        // GeminiLiveSession.micLoop discovered the denial. AssistantStrip guards its own door; the
        // wake word and the Android Auto voice button call straight into onTap and never did.
        val body = bodyOf("onTap")
        val micAt = body.indexOf("hasMicPermission()")
        val startAt = body.indexOf("startConversation(")
        val resumeAt = body.indexOf("resumeWarm(")
        assertTrue("onTap no longer checks the microphone permission at all", micAt >= 0)
        assertTrue("the mic check must precede resuming a warm socket", micAt in 0 until resumeAt)
        assertTrue("the mic check must precede a cold connect", micAt in 0 until startAt)
    }

    @Test
    fun `every early return in requestSpeak reports first`() {
        // The proactive side of the same enumeration. Its refusals are logged rather than flashed
        // unless the caller passed `userInitiated` - a raise nobody asked for must not put an error
        // over whatever the person is doing - but NONE of them may return without reporting, which
        // is what all of them did before 2026-09-07.
        val lines = bodyOf("requestSpeak").lines()
        var examined = 0
        lines.forEachIndexed { index, line ->
            if (!BARE_RETURN.containsMatchIn(line)) return@forEachIndexed
            examined++
            val previous = lines.take(index).lastOrNull { it.isNotBlank() }.orEmpty()
            // A `return` that shares its line with the guard must be reported ON that line;
            // only a `return` standing alone may lean on the statement above it. Without
            // that split, a silent one-liner guard passed because the NEIGHBOURING guard's
            // refuse() happened to be the previous line - found by mutating the source and
            // watching this scan stay green, which is the only way to learn that a check
            // like this is looser than it reads.
            val standalone = line.trim() == "return" || line.trim() == "return }"
            val reachable = if (standalone) line + "\n" + previous else line
            assertTrue(
                "requestSpeak line ${index + 1} returns without reporting: \"${line.trim()}\"",
                (REPORTS_OR_HANDS_OFF + "speakAndListen(").any { reachable.contains(it) },
            )
        }
        // CLAUDE.md sec 4 rule 6, applied to a test instead of to an ingest: a check that passes
        // when nothing parsed is not a check. If BARE_RETURN stops matching - a reformat, a Kotlin
        // idiom this regex does not know - every assertion in the loop above is skipped and the
        // test goes green having examined nothing at all.
        assertEquals("the return scanner matched nothing, so it proved nothing", 1, examined)
    }

    @Test
    fun `no branch of requestSpeak drops a line without reporting it`() {
        // The three branches that speak on an existing socket all go through a call that can FAIL
        // without throwing: sendText and speakOnWarm both return false when OkHttp's socket is
        // already closing. Ignoring that Boolean is how a proactive line vanished with nothing
        // logged and nothing said, so every one of them is checked here.
        val body = bodyOf("requestSpeak")
        assertTrue(
            "the mid-conversation branch ignores sendText's result again",
            body.contains("if (!s.sendText(prompt)) refuse("),
        )
        assertTrue(
            "the warm branch ignores speakOnWarm's result again",
            body.contains("if (!s.speakOnWarm(prompt)) refuse("),
        )
    }

    @Test
    fun `no refusal path in the controller emits a bare notice any more`() {
        // Every driver-facing refusal goes through refuse(), which logs the sentence whether or not
        // it is shown. Direct showNotice() calls are still correct for things that are NOT
        // refusals, and there are exactly five in the file:
        //   1. inside refuse() itself - the one call every refused ask funnels through;
        //   2. consumeThreadLossNotice - a reconnect that lost the conversation thread;
        //   3. the Idle branch's thirty-minute forgotten-conversation backstop;
        //   4. the Closed branch's fault banner for a connection that died mid-chat;
        //   5. handleToolCall, when a tool response could not be sent because the socket had gone.
        // The count is pinned rather than the call banned: a sixth one should be a decision
        // somebody makes out loud.
        val direct = Regex("CompanionPhase\\.showNotice\\(").findAll(controllerSource()).count()
        assertEquals(
            "a new CompanionPhase.showNotice() appeared in LiveSessionController. If it is a " +
                "refused ask, route it through refuse() so the reason is logged even when the " +
                "screen stays quiet; if it is not, update this count and say which it is.",
            5,
            direct,
        )
    }

    // --- source access ---------------------------------------------------

    /** `LiveSessionController.kt`, comments stripped. */
    private fun controllerSource(): String =
        stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())

    /**
     * The body of `fun <name>(` in `LiveSessionController.kt`, brace-matched.
     *
     * Brittle by nature, and worth it: the alternative is no coverage at all of the wiring, and the
     * wiring is where the defect lived. A rename breaks this loudly rather than passing quietly.
     */
    private fun bodyOf(name: String): String {
        val source = controllerSource()
        val signature = source.indexOf("fun $name(")
        if (signature < 0) fail("LiveSessionController no longer declares fun $name(")
        val open = source.indexOf('{', signature)
        if (open < 0) fail("could not find the body of fun $name(")
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
            i++
        }
        fail("unbalanced braces reading fun $name(")
        error("unreachable")
    }

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/kevin/legion")
            if (candidate.isDirectory) return candidate
            val here = File(dir, "src/main/java/com/kevin/legion")
            if (here.isDirectory) return here
            dir = dir.parentFile
        }
        fail(
            "Could not locate the main source tree from ${System.getProperty("user.dir")} - this " +
                "test cannot silently pass, see its doc comment.",
        )
        error("unreachable")
    }

    /**
     * `//` and block comments removed, newlines kept so reported line numbers still line up -
     * the same job the hand-rolled stripper in
     * [com.kevin.legion.calendar.NoCalendarContractTest] does, needed here for the same reason:
     * LiveSessionController's own comments describe the very silent returns being banned above, so
     * an unstripped scan would flag the explanation of the fix as the bug.
     *
     * **A block comment is replaced by its own newlines, not by nothing.** Collapsing it would join
     * the line above to the line below, and the previous-non-blank-line lookup in the scans above
     * reads exactly that adjacency.
     *
     * Known limit, stated rather than hidden: this does not understand string literals, so a `//`
     * or a block-comment opener inside one would be stripped as though it were a comment.
     * `LiveSessionController.kt` contains neither today, and if that changes the scans above break
     * loudly rather than quietly passing.
     */
    private fun stripComments(text: String): String {
        val withoutBlocks = BLOCK_COMMENT.replace(text) { match ->
            "\n".repeat(match.value.count { it == '\n' })
        }
        return LINE_COMMENT.replace(withoutBlocks, "")
    }

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("//[^\n]*")

        /** A `return` that leaves the function, as opposed to `return@launch` / `return@collect`. */
        val BARE_RETURN = Regex("(^|[^@\\w])return\\s*(\\}|$|;)")

        /**
         * What makes a `return` acceptable: it reported the refusal, or it handed off to a path
         * that goes on to speak (resuming a warm socket, or cold-connecting one).
         */
        val REPORTS_OR_HANDS_OFF = listOf("refuse(", "resumeWarm(", "startConversation(")
    }
}
