@file:Suppress("MaxLineLength", "ReturnCount", "EmptyFunctionBlock") // test fixtures: invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.service.Phase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * How the laptop pipeline is wired into the controller, read from source (the controller needs a
 * live Context, a socket and Room to construct, the same limit LiveSessionLifecycleTest states),
 * plus the bridge's detach rule.
 */
class CascadeWiringTest {

    @Test
    fun `the engine setting is read once per conversation, at the one door a tap and the wake word share`() {
        val controller = controller()
        assertEquals("read exactly once in the controller", 1, Regex("VoiceEngineSettings\\.engine\\(").findAll(controller).count())
        val onTap = bodyOf(controller, "onTap")
        val readAt = onTap.indexOf("VoiceEngineSettings.engine(")
        assertTrue("the setting is read in onTap", readAt >= 0)
        // After every guard, so a refused tap opens nothing on either engine.
        for (guard in listOf("hasMicPermission()", "isOnline()", "hasKey()", "isInCall")) {
            assertTrue("$guard must come before the engine choice", onTap.indexOf(guard) in 0 until readAt)
        }
        assertTrue(onTap.substring(readAt).contains("openCascade("))
        // The session never re-reads it mid-conversation.
        assertFalse(session().contains("VoiceEngineSettings.engine("))
    }

    @Test
    fun `a tap during a laptop conversation goes to it before anything else`() {
        val onTap = bodyOf(controller(), "onTap")
        val cascadeAt = onTap.indexOf("onTapCascade(")
        assertTrue(cascadeAt >= 0)
        assertTrue(cascadeAt < onTap.indexOf("interruptByTap()"))
    }

    @Test
    fun `every Live door either refuses or waits for the laptop conversation to release`() {
        val c = controller()
        assertTrue(bodyOf(c, "requestSpeak").contains("speakOnCascade("))
        assertTrue(bodyOf(c, "onBrief").contains("endCascadeThen("))
        assertTrue(bodyOf(c, "onLookByHand").contains("endCascadeThen("))
        assertTrue(bodyOf(c, "destroy").contains("cascade?.destroy()"))
        assertTrue(bodyOf(c, "newConversation").contains("cascade?.close("))
        assertTrue(bodyOf(c, "voiceOwnsPhase").contains("cascade != null"))
    }

    @Test
    fun `the hand-over to Live runs only after the session released everything`() {
        val s = session()
        val body = bodyOf(s, "handOver")
        val releaseAt = body.indexOf("release(")
        val liveAt = body.indexOf("host.handOverToLive(")
        assertTrue(releaseAt >= 0 && liveAt > releaseAt)
        val release = bodyOf(s, "releaseNow")
        for (step in listOf("cancelAndJoin()", "player?.release()", "exit(", "abandonFocus()", "MicArbiter.release(", "backend?.close(")) {
            assertTrue("releaseNow() no longer does $step", release.contains(step))
        }
    }

    // --- the cloud fallback (Kevin, 2026-11-15) -------------------------------------------------

    @Test
    fun `a laptop that cannot open hands the conversation to the cloud voice, Live only if that cannot start`() {
        val open = bodyOf(session(), "openBackend")
        val routed = open.indexOf("CascadeRules.nextAfter(SpeechBackend.Kind.LAPTOP")
        assertTrue(routed >= 0)
        assertTrue("the cloud voice is built after the laptop refusal", open.indexOf("cloudSpeech(scope)") > routed)
        assertTrue(open.contains("handOver("))
        // Turn models missing: Live, before any backend is tried (the cloud voice needs them too).
        val openNow = bodyOf(session(), "openNow")
        assertTrue(openNow.indexOf("\"turn_models_unavailable\"") in 0 until openNow.indexOf("openBackend("))
    }

    @Test
    fun `a laptop failure mid-turn moves to the cloud before it would ever go to Live, and never asks the model twice`() {
        val s = session()
        for (name in listOf("recoverWords", "revoice", "onScriptedFailed", "onBackendDropped")) {
            val body = bodyOf(s, name)
            val swap = body.indexOf("swapToCloud(")
            assertTrue("$name must try the cloud voice", swap >= 0)
            assertTrue("$name must try the cloud before Live", body.indexOf("handOver(") > swap)
            assertFalse("$name must not run the model again", body.contains(".answer("))
        }
        val runTurn = bodyOf(s, "runTurn")
        assertTrue(runTurn.contains("recoverWords(u,"))
        assertEquals("a spoken turn answers through answerTurn, once", 1, Regex("answerTurn\\(").findAll(runTurn).count())
        val answer = bodyOf(s, "answerTurn")
        assertEquals("one model call per turn", 1, Regex("textLoop!!\\.answer\\(").findAll(answer).count())
        assertTrue(answer.contains("revoice("))
    }

    @Test
    fun `the swap keeps the conversation and is idempotent`() {
        val swap = bodyOf(session(), "swapToCloud")
        assertTrue("a late report of the same laptop failure changes nothing", swap.contains("if (current !== failed)"))
        assertTrue(swap.contains("failed.close("))
        assertTrue(swap.contains("replan()"))
        for (kept in listOf("history", "textLoop", "prefix", "micJob")) {
            assertFalse("the swap must keep $kept", Regex("\\b$kept\\s*=").containsMatchIn(swap))
        }
    }

    @Test
    fun `the mic feeds turns under the swap lock, and a turn in progress keeps its plan`() {
        val s = session()
        assertTrue(bodyOf(s, "startMic").contains("synchronized(turnLock) { turnTaker?.feed("))
        val replan = bodyOf(s, "replan")
        assertTrue(replan.contains("synchronized(turnLock)"))
        assertTrue(replan.contains("if (currentUtt != null)"))
        assertTrue(bodyOf(s, "onTurnEnd").contains("if (replanPending) replan()"))
    }

    // --- Live retired (Kevin, 2026-11-15: "replace the sts live pipeline with it") ---------------

    @Test
    fun `every voice door opens the cascade before any Live path while Live is retired`() {
        val c = controller()
        val onTap = bodyOf(c, "onTap")
        assertTrue(onTap.contains("if (engine != VoiceEngine.LIVE)"))
        assertTrue(onTap.indexOf("openCascade(") < onTap.indexOf("startConversation("))
        for ((door, cascadeCall) in listOf("onBrief" to "briefOnCascade()", "requestSpeak" to "speakOnCascade(")) {
            val body = bodyOf(c, door)
            val retired = body.indexOf("VoiceEngineSettings.LIVE_RETIRED")
            assertTrue("$door must check LIVE_RETIRED", retired >= 0)
            assertTrue("$door must hand to the cascade first", body.indexOf(cascadeCall) > retired)
            for (live in listOf("startConversation(", "startProactive(", "speakAndListen(", "sendText(")) {
                val at = body.indexOf(live)
                assertTrue("$door reaches $live before the cascade", at < 0 || at > body.indexOf(cascadeCall))
            }
        }
        val look = bodyOf(c, "onLookByHand")
        assertTrue(look.indexOf("VoiceRefusal.LOOK_NEEDS_LIVE") in 0 until look.indexOf("startConversation("))
    }

    @Test
    fun `a cascade that fails ends in words, never in Live, while Live is retired`() {
        val handOver = bodyOf(session(), "handOver")
        val retired = handOver.indexOf("if (VoiceEngineSettings.LIVE_RETIRED)")
        assertTrue(retired >= 0)
        assertTrue(handOver.indexOf("host.closed(") in retired until handOver.indexOf("host.handOverToLive("))
        assertTrue(VoiceEngineSettings.LIVE_RETIRED)
    }

    @Test
    fun `the cloud voice opens with the bundled clip, the laptop with its own voice`() {
        val scripted = bodyOf(session(), "speakScripted")
        assertTrue(scripted.indexOf("playCue()") in 0 until scripted.indexOf("newSpeaker("))
        assertTrue(bodyOf(session(), "playCue").contains("ListenCue.next("))
    }

    @Test
    fun `the car's hands-free mic and the Bluetooth watch carry over from Live`() {
        val s = session()
        assertTrue(s.contains("audioRoute.enterBluetoothMic("))
        assertTrue(bodyOf(s, "startMic").indexOf("audioRoute.awaitBluetoothMic()") in 0 until bodyOf(s, "startMic").indexOf("CascadeMic("))
        assertTrue(s.contains("audioRoute.watchForBluetooth"))
        assertTrue(bodyOf(s, "releaseNow").contains("audioRoute.stopWatching()"))
    }

    @Test
    fun `phone faults are charged to the phone on any voice`() {
        val s = session()
        assertTrue(bodyOf(s, "startMic").contains("phoneFault = true"))
        assertTrue(bodyOf(s, "onCrash").contains("phoneFault = true"))
        assertTrue(Regex("silero_failed\", phoneFault = true").containsMatchIn(s))
    }

    @Test
    fun `the Live session itself is not touched by the laptop pipeline`() {
        val live = File(sourceRoot(), "service/GeminiLiveSession.kt").readText()
        assertFalse(live.contains("cascade", ignoreCase = true))
    }

    @Test
    fun `a detached bridge moves nothing on screen`() {
        val phases = mutableListOf<Phase>()
        var toLive = 0
        val b = CascadeBridge(
            post = { it() },
            setPhase = { p, _ -> phases.add(p) },
            showNotice = {},
            tool = { _, _, _, _, _ -> JSONObject() },
            transcript = { _, _ -> },
            onCrisis = {},
            tone = {},
            toLive = { _, _ -> toLive++ },
            onClosed = {},
        )
        b.phase(Phase.LISTENING, "Listening...")
        b.handOverToLive(fromWakeWord = true, opener = null)
        b.handOverToLive(fromWakeWord = true, opener = null) // once only
        b.phase(Phase.SPEAKING, "late")
        assertEquals(listOf(Phase.LISTENING), phases)
        assertEquals(1, toLive)
    }

    // --- source access ----------------------------------------------------------------------------

    private fun controller() = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())

    private fun session() = stripComments(File(sourceRoot(), "service/cascade/CascadeSession.kt").readText())

    private fun bodyOf(source: String, name: String): String {
        val signature = Regex("fun $name\\(").find(source)?.range?.first ?: fail("no fun $name(").let { -1 }
        val open = source.indexOf('{', signature)
        val eq = source.indexOf('=', signature)
        // An expression body (`fun x(): T = ...`): take the rest of that statement.
        if (eq in (signature + 1) until open && source.substring(eq, open).lines().size > 1) {
            return source.substring(eq, source.indexOf("\n\n", eq).let { if (it < 0) source.length else it })
        }
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
        fail("unbalanced braces in $name")
        return ""
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
        fail("source root not found")
        return File(".")
    }

    private fun stripComments(text: String): String =
        LINE_COMMENT.replace(BLOCK_COMMENT.replace(text) { "\n".repeat(it.value.count { c -> c == '\n' }) }, "")

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("(?<![:\"])//[^\n]*")
    }
}
