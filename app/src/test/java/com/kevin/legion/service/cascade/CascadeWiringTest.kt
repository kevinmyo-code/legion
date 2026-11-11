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
        val cascadeAt = onTap.indexOf("onTapCascade()")
        assertTrue(cascadeAt >= 0)
        assertTrue(cascadeAt < onTap.indexOf("interruptByTap()"))
    }

    @Test
    fun `every Live door either refuses or waits for the laptop conversation to release`() {
        val c = controller()
        assertTrue(bodyOf(c, "requestSpeak").contains("refuseUnderCascade("))
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
        for (step in listOf("cancelAndJoin()", "player?.release()", "exit(", "abandonFocus()", "MicArbiter.release(", "conn?.close(")) {
            assertTrue("releaseNow() no longer does $step", release.contains(step))
        }
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
