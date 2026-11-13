package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Kevin, 2026-11-05: *"stop keeping a voice session open while idle; only open one when you tap or
 * say the wake word."* The A25's prod log (build 263092342) showed the loop this ends: a
 * conversation ends, a socket opens at once, Google closes it for "client inactivity" about two
 * minutes later, it reopens, for 40+ minutes, with "superseded" / "already connected to an existing
 * client" collisions among them.
 *
 * Two halves. The pure decisions in [LiveSessionLifecycle] are walked directly. The wiring - that
 * nothing opens a socket on start, on a close or on a refresh, and that there is one door - lives
 * in [LiveSessionController] and [AriaForegroundService], which need a Context to construct, so it
 * is pinned by reading their source the way [LiveSessionControllerRefusalTest] does.
 */
class LiveSessionLifecycleTest {

    private fun afterClose(
        reason: String,
        conversationMode: Boolean = false,
        destroyed: Boolean = false,
        coldRetryArmed: Boolean = false,
        everConnected: Boolean = true,
        alreadyRetried: Boolean = false,
    ) = LiveSessionLifecycle.afterClose(reason, conversationMode, destroyed, coldRetryArmed, everConnected, alreadyRetried)

    // --- no session after a conversation ends, and none on an idle close ------------------------

    @Test
    fun `a conversation that ends opens no new session`() {
        // FollowUpExpired, "that will be all", end_conversation and a tap to stop all close "stopped".
        assertEquals(LiveSessionLifecycle.AfterClose.NOTHING, afterClose("stopped", conversationMode = true))
        // The 30 minute backstop's parked socket expiring.
        assertEquals(LiveSessionLifecycle.AfterClose.NOTHING, afterClose("warm expired"))
    }

    @Test
    fun `client inactivity on an idle socket does not reconnect`() {
        assertEquals(
            LiveSessionLifecycle.AfterClose.NOTHING,
            afterClose("Session closed due to client inactivity."),
        )
        // A speak-only proactive line finishing, and the network going away while nobody talks.
        assertEquals(LiveSessionLifecycle.AfterClose.NOTHING, afterClose("idle"))
        assertEquals(
            LiveSessionLifecycle.AfterClose.NOTHING,
            afterClose("Unable to resolve host \"generativelanguage.googleapis.com\"", everConnected = false),
        )
    }

    @Test
    fun `a goAway opens nothing on its own`() {
        assertEquals(LiveSessionLifecycle.AfterClose.NOTHING, afterClose("goAway"))
        assertEquals(LiveSessionLifecycle.AfterClose.NOTHING, afterClose("goAway", conversationMode = true))
    }

    @Test
    fun `a destroyed controller opens nothing whatever the close`() {
        for (r in listOf(GeminiLiveSession.TURN_FALLBACK_REASON, "connection failed", "stopped")) {
            assertEquals(
                r,
                LiveSessionLifecycle.AfterClose.NOTHING,
                afterClose(r, conversationMode = true, destroyed = true, coldRetryArmed = true, everConnected = false),
            )
        }
    }

    // --- recovery while someone is talking or just tapped still works ---------------------------

    @Test
    fun `the turn-detector fallback mid-conversation resumes the conversation`() {
        assertEquals(
            LiveSessionLifecycle.AfterClose.RESUME_CONVERSATION,
            afterClose(GeminiLiveSession.TURN_FALLBACK_REASON, conversationMode = true),
        )
    }

    @Test
    fun `the turn-detector fallback with no conversation opens nothing`() {
        assertEquals(LiveSessionLifecycle.AfterClose.NOTHING, afterClose(GeminiLiveSession.TURN_FALLBACK_REASON))
    }

    @Test
    fun `a tap whose connect failed gets one cold retry, and only one`() {
        val tap = { retried: Boolean ->
            afterClose(
                "connection failed", conversationMode = true, coldRetryArmed = true,
                everConnected = false, alreadyRetried = retried,
            )
        }
        assertEquals(LiveSessionLifecycle.AfterClose.RETRY_COLD, tap(false))
        assertEquals(LiveSessionLifecycle.AfterClose.NOTHING, tap(true))
    }

    @Test
    fun `an already-connected refusal on a tap is retried cold`() {
        // The handle that drew it is dropped (never connected), so the retry goes out without one.
        assertEquals(
            LiveSessionLifecycle.AfterClose.RETRY_COLD,
            afterClose(
                "BidiGenerateContent session is already connected to an existing client",
                conversationMode = true, coldRetryArmed = true, everConnected = false,
            ),
        )
    }

    @Test
    fun `a proactive connect that failed is not retried`() {
        assertEquals(
            LiveSessionLifecycle.AfterClose.NOTHING,
            afterClose("connection failed", conversationMode = false, coldRetryArmed = false, everConnected = false),
        )
    }

    // --- the race guard -----------------------------------------------------------------------

    @Test
    fun `only the session currently held may drive the controller`() {
        val held = Any()
        val superseded = Any()
        assertTrue(LiveSessionLifecycle.accepts(source = held, current = held))
        assertFalse(LiveSessionLifecycle.accepts(source = superseded, current = held))
        assertFalse("a late event after the controller let go", LiveSessionLifecycle.accepts(source = held, current = null))
        assertFalse(LiveSessionLifecycle.accepts<Any>(source = null, current = null))
    }

    @Test
    fun `a raise never overwrites a connect that is in flight for a tap`() {
        assertEquals(
            LiveSessionLifecycle.SpeakRoute.REFUSE_CONNECT_IN_FLIGHT,
            LiveSessionLifecycle.speakRoute(hasSession = true, inConversation = false, warm = false, connectQueued = true),
        )
    }

    @Test
    fun `every other raise route is unchanged`() {
        assertEquals(
            LiveSessionLifecycle.SpeakRoute.COLD_START,
            LiveSessionLifecycle.speakRoute(hasSession = false, inConversation = false, warm = false, connectQueued = false),
        )
        assertEquals(
            LiveSessionLifecycle.SpeakRoute.FOLD_IN,
            LiveSessionLifecycle.speakRoute(hasSession = true, inConversation = true, warm = false, connectQueued = false),
        )
        assertEquals(
            LiveSessionLifecycle.SpeakRoute.SPEAK_ON_WARM,
            LiveSessionLifecycle.speakRoute(hasSession = true, inConversation = false, warm = true, connectQueued = false),
        )
        assertEquals(
            LiveSessionLifecycle.SpeakRoute.QUEUE_ON_CONNECT,
            LiveSessionLifecycle.speakRoute(hasSession = true, inConversation = false, warm = false, connectQueued = false),
        )
    }

    // --- the wiring, read from source -----------------------------------------------------------

    @Test
    fun `one door - a Live session is constructed in exactly one place`() {
        val hits = mainSources().filter { (_, code) -> SESSION_CTOR.containsMatchIn(code) }
            .flatMap { (file, code) -> SESSION_CTOR.findAll(code).map { file.name }.toList() }
        assertEquals(listOf("LiveSessionController.kt"), hits)
    }

    @Test
    fun `the one door supersedes the held session before it builds a new one`() {
        val body = bodyOf(controller(), "newSession")
        val supersedeAt = body.indexOf("supersede(")
        val ctorAt = SESSION_CTOR.find(body)?.range?.first ?: -1
        assertTrue("newSession no longer supersedes the held session", supersedeAt >= 0)
        assertTrue("newSession must supersede BEFORE it constructs", supersedeAt < ctorAt)
        assertTrue("newSession no longer guards events by identity", body.contains("LiveSessionLifecycle.accepts("))
    }

    @Test
    fun `superseding drops the resumption handle`() {
        val body = bodyOf(controller(), "supersede")
        assertTrue(body.contains("silentDestroy()"))
        assertTrue(body.contains("sessionResumeHandle = null"))
    }

    @Test
    fun `nothing prewarms - not on start, not on a close, not on a refresh`() {
        assertFalse("LiveSessionController still has a prewarm", controller().contains("prewarm("))
        assertFalse("AriaForegroundService still prewarms on start", service().contains("prewarm("))
        for (name in listOf("refreshIdleVoice", "newConversation")) {
            val body = bodyOf(controller(), name)
            for (opener in OPENERS) {
                assertFalse("$name opens a socket via $opener", body.contains(opener))
            }
        }
    }

    @Test
    fun `the typed panel opens no Live socket`() {
        val body = bodyOf(controller(), "onTyped")
        for (opener in OPENERS) assertFalse("onTyped opens a socket via $opener", body.contains(opener))
    }

    @Test
    fun `a tap and the wake word open a session through the one door`() {
        // The wake word fires ACTION_TALK with EXTRA_FROM_WAKE_WORD, which the service hands to
        // onTap(fromWakeWord = ...): the same door as a tap.
        assertTrue(service().contains("sessionController.onTap(fromWakeWord = "))
        val onTap = bodyOf(controller(), "onTap")
        assertTrue(onTap.contains("startConversation("))
        assertTrue(bodyOf(controller(), "startConversation").contains("newSession()"))
    }

    @Test
    fun `a tap or the wake word greets from his day, with the scripted line as the fallback`() {
        // Voice cost audit 2026-11-10, F2 removed GREETING_PROMPT / WAKE_ACK_PROMPT; ADR 0091
        // (2026-11-13) amends it: the greeting is built from the facts pack ([LiveGreeting]) and the
        // scripted line stays as what plays when the pack or the model is slow or unreachable.
        val code = controller()
        assertFalse("GREETING_PROMPT is back", code.contains("GREETING_PROMPT"))
        assertFalse("WAKE_ACK_PROMPT is back", code.contains("WAKE_ACK_PROMPT"))
        val start = bodyOf(code, "startConversation")
        assertTrue("a cold open no longer plays the scripted line", start.contains("openListening(s)"))
        assertTrue("the cue timing is one constant", start.contains("CUE_TIMING"))
        assertTrue(
            "ON_CONNECT opens the greeting when the socket is up, scripted line when there is no pack",
            bodyOf(code, "handleEvent").contains("greeting.open(held, prep)") &&
                bodyOf(code, "handleEvent").contains("openListening(held, warm = true)"),
        )
        assertTrue("the pack is fetched at the tap", start.contains("greeting.prepare()"))
        assertTrue(
            "the greeting's fallback is the scripted open",
            code.contains("scripted = { openListening(it, warm = true) }"),
        )
        assertTrue(
            "the live context must ride the system instruction, not an opener turn",
            start.contains("LiveOpenContext.systemInstruction("),
        )
        assertTrue(bodyOf(code, "resumeWarm").contains("greeting.open(s, greeting.prepare()"))
        val open = bodyOf(code, "openListening")
        assertTrue(open.contains("ListenCue.next("))
        assertFalse("openListening must not send text to the model", open.contains("sendText("))
    }

    @Test
    fun `simple commands are held on the phone only behind the setting, and a hit ends the chat`() {
        // Phase 2 (Kevin: "we offload as much as we can to the phones own speech recognizer").
        val code = controller()
        // Split out of openListening so the greeting path arms the same gate (ADR 0091).
        assertTrue(bodyOf(code, "openListening").contains("armLocalFirstTurn(s)"))
        val open = bodyOf(code, "armLocalFirstTurn")
        assertTrue(open.contains("VoiceTurnSettings.localCommands(appContext)"))
        assertTrue(open.contains("s.armLocalFirstTurn()"))
        assertTrue(bodyOf(code, "handleEvent").contains("is LiveEvent.LocalCommand -> runLocalCommand("))
        val run = bodyOf(code, "runLocalCommand")
        assertTrue(run.contains("LocalCommandRunner.run("))
        assertTrue("a handled command must end the conversation", run.contains("session?.stop()"))
        assertFalse("the local path never asks the model", run.contains("sendText("))
    }

    // --- source access (same approach and limits as LiveSessionControllerRefusalTest) -----------

    private fun controller(): String = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())

    private fun service(): String = stripComments(File(sourceRoot(), "service/AriaForegroundService.kt").readText())

    private fun mainSources(): List<Pair<File, String>> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it to stripComments(it.readText()) }.toList()

    private fun bodyOf(source: String, name: String): String {
        val signature = source.indexOf("fun $name(")
        if (signature < 0) fail("no fun $name(")
        val open = source.indexOf('{', signature)
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
        fail("Could not locate the main source tree from ${System.getProperty("user.dir")}")
        error("unreachable")
    }

    private fun stripComments(text: String): String =
        LINE_COMMENT.replace(BLOCK_COMMENT.replace(text) { "\n".repeat(it.value.count { c -> c == '\n' }) }, "")

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("//[^\n]*")

        /** A construction, not the class declaration (`class GeminiLiveSession(`). */
        val SESSION_CTOR = Regex("(?<!class )\\bGeminiLiveSession\\(")

        /** Every call that leads to a socket being opened. */
        val OPENERS = listOf("newSession(", "startConversation(", "startProactive(", "speakAndListen(", "resumeWarm(")
    }
}
