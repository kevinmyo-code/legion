package com.kevin.legion.service.cascade

import com.kevin.legion.testutil.MainSource
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
    fun `release order is chime, player, route watch, earpiece, call audio`() {
        val release = bodyOf(session(), "releaseNow")
        val order = listOf(
            "playCloseCue(reason)",
            "step(\"player\")",
            "step(\"route_watch\")",
            "step(\"earpiece\") { earpiece.stop() }",
            "step(\"call_audio\")",
        ).map { it to release.indexOf(it) }
        for ((text, at) in order) assertTrue("$text is in releaseNow", at >= 0)
        // The chime plays on the route in use (the earpiece too) before that route is let go.
        assertEquals(order.sortedBy { it.second }, order)
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

    /**
     * Kevin, 2026-11-24, in the car: with the head unit's Bluetooth connected, Alfred did not hear him
     * at all. Every buffer is measured, and a hands-free mic that sends nothing audible is given up on
     * for the phone's own, inside the conversation.
     */
    @Test
    fun `every mic buffer is measured and a deaf hands-free mic hands over to the phone's`() {
        val s = session()
        val onBuffer = bodyOf(s, "onMicBuffer")
        val measured = onBuffer.indexOf("if (readLevel(buf, n)) return")
        assertTrue("the level is read on every buffer", measured >= 0)
        assertTrue("before anything reaches a turn", measured < onBuffer.indexOf("taker.feed(buf, n, playbackActive)"))
        val level = bodyOf(s, "readLevel")
        assertTrue("one peak per buffer", level.contains("MicSignal.peakAmplitude(buf, n)"))
        assertTrue("the screen's bar reads it too", level.contains("MicLevel.publish(peak)"))
        assertTrue("the whole window's loudest sample, not one buffer's", level.contains("if (peak > capturePeak)"))
        assertTrue("the decision is BluetoothMic's", level.contains("BluetoothMic.fallBack("))
        assertTrue("only on the hands-free mic", level.contains("onBluetoothMic = callAudio?.onBluetoothMic == true"))
    }

    @Test
    fun `the fallback drops the hands-free route before it opens the phone's mic`() {
        val back = bodyOf(session(), "fallBackToPhoneMic")
        val closed = back.indexOf("micJob = null")
        val left = back.indexOf("it.exit(\"hands-free mic deaf\")")
        val settle = back.indexOf("delay(MicReopen.settleMs(")
        val told = back.indexOf("host.notice(")
        val opened = back.indexOf("startMic()")
        for ((what, at) in listOf("capture closed" to closed, "route left" to left, "settle" to settle,
            "said in words" to told, "mic opened" to opened)) {
            assertTrue("$what is in the fallback", at >= 0)
        }
        assertTrue("the capture closes before the route is let go", closed < left)
        assertTrue("the new route settles before the capture opens", left < settle && settle < opened)
        assertTrue("he is told before it listens again, so he knows to say it again", told < opened)
        assertTrue("and the mode's own flag is cleared", back.contains("callAudio = null"))
    }

    @Test
    fun `nothing else reads the mic level, so there is one level path`() {
        val mic = stripComments(File(sourceRoot(), "service/cascade/CascadeMic.kt").readText())
        assertFalse("the mic loop no longer publishes its own level", mic.contains("MicLevel.publish"))
        assertFalse(mic.contains("MicSignal."))
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

    private fun session() = stripComments(MainSource.read(MainSource.CASCADE_SESSION))

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
