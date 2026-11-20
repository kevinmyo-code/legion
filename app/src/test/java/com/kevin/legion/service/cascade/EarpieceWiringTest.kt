package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the conversation wires the SPEAKER / CALL toggle, checked in the source because the session
 * needs a whole voice stack to run: the route is released on every exit before the mode is restored,
 * every way out of call audio keeps the earpiece, and only [com.kevin.legion.service.turn.CallAudioMode]
 * ever enters the communication mode (so LEGION never looks like a foreign call to itself).
 */
class EarpieceWiringTest {

    @Test
    fun `release stops the earpiece follower and proximity lock before call audio is restored`() {
        val release = bodyOf(session(), "releaseNow")
        val stop = release.indexOf("step(\"earpiece\") { earpiece.stop() }")
        assertTrue("the earpiece has its own release step", stop >= 0)
        assertTrue("before call audio exits", stop < release.indexOf("step(\"call_audio\")"))
    }

    @Test
    fun `call audio is entered for barge-in or the earpiece, and the follower starts with the route`() {
        val enter = bodyOf(session(), "enterRoute")
        assertTrue(enter.contains("VoiceOutputRule.callAudio(barge, outlet)"))
        assertTrue(enter.contains("earpiece.follow("))
        assertTrue(bodyOf(session(), "enterCallAudio").contains("mode.enter(onEarpiece)"))
    }

    @Test
    fun `the barge-in exits keep call audio on the earpiece, Bluetooth still takes it`() {
        val s = session()
        val bargeOff = s.substring(s.indexOf("override fun onBargeInDisabled("))
        assertTrue(bargeOff.substring(0, bargeOff.indexOf("exit(\"self-interrupts\")")).contains("onEarpiece == true) return@launch"))
        assertTrue(s.contains("callAudio?.takeUnless { it.onBluetoothMic || it.onEarpiece }"))
        assertTrue(bodyOf(s, "onBluetoothConnected").contains("callAudio?.let { if (it.isActive) it.exit(\"bluetooth connected\") }"))
    }

    @Test
    fun `a midway entry never cuts the reply in flight`() {
        val midway = bodyOf(session(), "enterEarpieceMidway")
        val wait = midway.indexOf("while (speaking")
        assertTrue(wait >= 0)
        assertTrue("waits before it rebuilds the player", wait < midway.indexOf("player?.rebuild()"))
    }

    @Test
    fun `only CallAudioMode enters the communication mode`() {
        val setters = sourceRoot().walkTopDown().filter { it.extension == "kt" }
            .filter { stripComments(it.readText()).contains("mode = AudioManager.MODE_IN_COMMUNICATION") }
            .map { it.name }.toList()
        assertEquals(listOf("CallAudioMode.kt"), setters)
        val earpiece = stripComments(File(sourceRoot(), "service/cascade/EarpieceRoute.kt").readText())
        assertTrue("the follower goes through CallAudioMode", !earpiece.contains("setCommunicationDevice") && !earpiece.contains(".mode ="))
    }

    private fun session() = stripComments(File(sourceRoot(), "service/cascade/CascadeSession.kt").readText())

    private fun bodyOf(source: String, name: String): String {
        val signature = Regex("fun $name\\(").find(source)?.range?.first ?: error("no fun $name(")
        val open = source.indexOf('{', signature)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, i)
            }
        }
        error("unbalanced braces in $name")
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
        error("no source root")
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "")
}
