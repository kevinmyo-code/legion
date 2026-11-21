package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val opening = "VoiceOutputRule.opening(inHand, fromWakeWord, opener != null, test != null)"
        val begin = enter.indexOf("earpiece.begin($opening)")
        assertTrue("the opening kind is set first", begin >= 0)
        assertTrue(begin < enter.indexOf("earpiece.wantsEarpiece()"))
        assertTrue(enter.contains("VoiceOutputRule.callAudio(barge, onEarpiece)"))
        assertTrue(enter.contains("earpiece.follow("))
        assertTrue(bodyOf(session(), "enterCallAudio").contains("mode.enter(onEarpiece)"))
    }

    @Test
    fun `the barge-in exits keep call audio on the earpiece, Bluetooth still takes it`() {
        val s = session()
        val bargeOff = s.substring(s.indexOf("override fun onBargeInDisabled("))
        val beforeExit = bargeOff.substring(0, bargeOff.indexOf("exit(\"self-interrupts\")"))
        assertTrue(beforeExit.contains("onEarpiece == true) return@launch"))
        assertTrue(s.contains("callAudio?.takeUnless { it.onBluetoothMic || it.onEarpiece }"))
        val bluetooth = bodyOf(s, "onBluetoothConnected")
        assertTrue(bluetooth.contains("callAudio?.let { if (it.isActive) it.exit(\"bluetooth connected\") }"))
    }

    @Test
    fun `a midway entry never cuts the reply in flight`() {
        val midway = bodyOf(session(), "enterEarpieceMidway")
        val wait = midway.indexOf("while ((speaking || playbackActive)")
        assertTrue("waits for a reply and for a filler clip", wait >= 0)
        assertTrue("waits before it rebuilds the player", wait < midway.indexOf("player?.rebuild()"))
        val recheck = midway.indexOf("if (!earpiece.wantsEarpiece()) return")
        assertTrue("re-reads the choice after the wait (a flip back while he spoke)", recheck > wait)
        assertTrue(recheck < midway.indexOf("enterCallAudio("))
    }

    @Test
    fun `only the app's own taps say the phone is in hand`() {
        val hand = "AriaForegroundService.EXTRA_IN_HAND, true"
        for (inApp in listOf("ui/assistant/AssistantVoice.kt", "ui/dumb/DumbHomeScreen.kt", "ui/dumb/MatrixTalk.kt")) {
            assertTrue(inApp, File(sourceRoot(), inApp).readText().contains(hand))
        }
        val elsewhere = listOf(
            "service/WakeWordEngine.kt",
            "service/speaker/SpeakerAskNotifier.kt",
            "car/LegionMediaLibraryService.kt",
        )
        for (other in elsewhere) assertFalse(other, File(sourceRoot(), other).readText().contains("EXTRA_IN_HAND"))
        val service = stripComments(File(sourceRoot(), "service/AriaForegroundService.kt").readText())
        assertTrue(service.contains("inHand = intent.getBooleanExtra(EXTRA_IN_HAND, false)"))
        val controller = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())
        val tapOpen = "openCascade(fromWakeWord, oneShot, laptopFirst = engine == VoiceEngine.LAPTOP, inHand = inHand)"
        assertTrue("a tap passes it on", bodyOf(controller, "onTap").contains(tapOpen))
        assertTrue("and it reaches the session", bodyOf(controller, "openCascade").contains("inHand = inHand,"))
    }

    @Test
    fun `only CallAudioMode enters the communication mode`() {
        val setters = sourceRoot().walkTopDown().filter { it.extension == "kt" }
            .filter { stripComments(it.readText()).contains("mode = AudioManager.MODE_IN_COMMUNICATION") }
            .map { it.name }.toList()
        assertEquals(listOf("CallAudioMode.kt"), setters)
        val earpiece = stripComments(File(sourceRoot(), "service/cascade/EarpieceRoute.kt").readText())
        assertFalse("the follower goes through CallAudioMode", earpiece.contains("setCommunicationDevice"))
        assertFalse("the follower never sets the mode", earpiece.contains(".mode ="))
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
