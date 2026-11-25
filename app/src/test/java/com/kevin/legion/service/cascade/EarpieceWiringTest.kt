package com.kevin.legion.service.cascade

import com.kevin.legion.testutil.MainSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the conversation wires the earpiece and the Call button, checked in the source because the session
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
        val tap = bodyOf(controller, "onTap")
        assertTrue("a tap passes it on", tap.contains("inHand = inHand,"))
        assertTrue("and it reaches the session", bodyOf(controller, "openCascade").contains("inHand = inHand,"))
    }

    /**
     * The call's intent travels with the conversation and is never stored: the Call button's extra
     * reaches the session, the session hands it to its own [EarpieceRoute], and nothing in the
     * earpiece path reads a preference, so a phone that saved CALL before 2026-11-24 does not send a
     * plain orb tap to the earpiece.
     */
    @Test
    fun `the call intent is per conversation and read from no preference`() {
        val service = stripComments(File(sourceRoot(), "service/AriaForegroundService.kt").readText())
        assertTrue(service.contains("call = intent.getBooleanExtra(EXTRA_CALL, false)"))
        val controller = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())
        assertTrue("a tap passes the call on", bodyOf(controller, "onTap").contains("call = call)"))
        assertTrue("and it reaches the session", bodyOf(controller, "openCascade").contains("call = call,"))
        assertTrue(session().contains("EarpieceRoute(ctx, audioManager, call = call)"))
        val earpiece = stripComments(File(sourceRoot(), "service/cascade/EarpieceRoute.kt").readText())
        assertFalse("no preference in the earpiece path", earpiece.contains("SharedPreferences"))
        assertFalse(earpiece.contains("getSharedPreferences"))
        val output = stripComments(File(sourceRoot(), "service/turn/VoiceOutput.kt").readText())
        assertFalse("the saved SPEAKER / CALL choice is gone", output.contains("KEY_CHOICE"))
        assertTrue("and the old file is deleted", output.contains("deleteSharedPreferences(LEGACY_PREFS)"))
    }

    /**
     * Kevin, 2026-11-24, in the car: with the head unit's Bluetooth connected, Alfred did not hear him
     * at all. Every buffer is measured, before every other gate, so the screen's bar keeps moving
     * through a mute and a call pause as it did when the mic loop published it.
     * [com.kevin.legion.service.turn.DeafMicFallbackTest] owns the decision itself.
     */
    @Test
    fun `every mic buffer is measured first, and only the session reads a level`() {
        val s = session()
        val onBuffer = bodyOf(s, "onMicBuffer")
        val measured = onBuffer.indexOf("if (readLevel(buf, n)) return")
        assertTrue("the level is read on every buffer", measured >= 0)
        for (gate in listOf("if (callPaused) return", "if (micMuted) return", "taker.feed(buf, n, playbackActive)")) {
            assertTrue("measured before $gate", measured < onBuffer.indexOf(gate))
        }
        val level = bodyOf(s, "readLevel")
        assertTrue("one peak per buffer", level.contains("MicSignal.peakAmplitude(buf, n)"))
        assertTrue("the screen's bar reads it too", level.contains("MicLevel.publish(peak)"))
        assertTrue("the window and the verdict are DeafMicFallback's", level.contains("deafMic.read("))
        assertTrue("and once the switch runs, the capture feeds no turn",
            level.contains("return deaf || deafMic.switching"))
        assertTrue("only on the hands-free mic", level.contains("onHandsFreeMic = onHandsFreeMic"))
        assertTrue("never on a live call", level.contains("inLiveCall = callLineUp.get()"))
        // Playing, muted, call-paused, or an open with no voice yet: not a moment to judge a mic in.
        assertTrue(
            "judging names all four",
            level.contains("!playbackActive && !micMuted && !callPaused && backend != null && player != null"),
        )
        val mic = stripComments(File(sourceRoot(), "service/cascade/CascadeMic.kt").readText())
        assertFalse("the mic loop no longer publishes its own level", mic.contains("MicLevel.publish"))
        assertFalse(mic.contains("MicSignal."))
    }

    /**
     * The 2026-11-24 review's blocker: the switch suspends for seconds on the same single thread as
     * the open path's "nothing is capturing, open one" check, so the claim has to cover it and the
     * capture field must not read as free inside it.
     */
    @Test
    fun `the deaf-mic switch holds the mic slot, and every reopen check honours it`() {
        val s = session()
        val back = bodyOf(s, "fallBackToPhoneMic")
        assertTrue("the whole switch runs inside the claim", back.contains("deafMic.handOver {"))
        assertFalse("and never lets go of the capture mid-switch", back.contains("micJob = null"))
        val reopens = Regex("micJob == null[^)]*").findAll(s).map { it.value }.toList()
        assertEquals("two places open the mic when none is capturing", 2, reopens.size)
        for (check in reopens) {
            assertTrue("a reopen check honours the hand-over: $check", check.contains("!deafMic.switching"))
        }
    }

    @Test
    fun `the fallback drops the hands-free route, then says out loud what did not happen`() {
        val back = bodyOf(session(), "fallBackToPhoneMic")
        val closed = back.indexOf("micJob?.cancelAndJoin()")
        val left = back.indexOf("it.exit(\"hands-free mic deaf\")")
        val rebuilt = back.indexOf("player?.rebuild()")
        val settle = back.indexOf("delay(MicReopen.settleMs(")
        val opened = back.indexOf("startMic(chime =")
        val said = back.indexOf("sayMissedLine(DEAF_MIC_LINE)")
        for ((what, at) in listOf("capture closed" to closed, "route left" to left, "player rebuilt" to rebuilt,
            "settle" to settle, "mic opened" to opened, "said out loud" to said)) {
            assertTrue("$what is in the fallback", at >= 0)
        }
        assertTrue("the capture closes before the route is let go", closed < left)
        assertTrue("the player is rebuilt off the dead route, before the settle", left < rebuilt && rebuilt < settle)
        assertTrue("the new route settles before the capture opens", settle < opened)
        assertTrue("and the mode's own flag is cleared", back.contains("callAudio = null"))
        // Seconds pass on a shared loop: a close, a call or a check-in's closer may have taken over.
        val rechecked = back.indexOf("if (closing.get() || callPaused || checkInStep == CheckInStep.DONE)")
        assertTrue("the state is checked again after the settle", rechecked in settle until opened)
        // Spoken like any other reply, which is what arms his whole follow-up window again at drain.
        assertTrue("he is told out loud, not only on screen", opened < said)
        assertTrue("the chime stands in only when there is no voice to say it with",
            back.contains("startMic(chime = backend == null || player == null)"))
        // The window armed before he spoke must not close the conversation during the switch.
        val stoodDown = back.indexOf("followUpEvent(FollowUpWindow.Event.TurnComplete)")
        assertTrue("the old window stands down first", stoodDown in 0 until settle)
        assertTrue("and something arms one again however it ends", back.contains("else backToListening()"))
        val missed = bodyOf(session(), "sayMissedLine")
        assertTrue("that path arms the window on a played line", missed.contains("afterReply()"))
        assertTrue("and on one that could not be said", missed.contains("backToListening()"))
    }

    @Test
    fun `the hands-free flag is cleared by every move off that route`() {
        val s = session()
        assertTrue("set only where the hands-free mic is entered",
            bodyOf(s, "enterRoute").contains("onHandsFreeMic = callAudio != null"))
        // The one door into call audio, a mid-conversation move to the earpiece included: from there
        // the capture is the phone's own, and a quiet room must not be announced as a deaf car.
        assertTrue(bodyOf(s, "enterCallAudio").contains("onHandsFreeMic = false"))
        assertTrue(bodyOf(s, "fallBackToPhoneMic").contains("onHandsFreeMic = false"))
        assertEquals("one place turns it on", 1, Regex("onHandsFreeMic = callAudio != null").findAll(s).count())
    }

    @Test
    fun `a window that heard no turn says so, with its route and its loudest sample`() {
        val s = session()
        val line = bodyOf(s, "heardNothingLine")
        assertTrue("silent once he has spoken", line.contains("if (heardSinceOpen)"))
        assertTrue("the route it listened on", line.contains("heard_nothing=true mic_route="))
        assertTrue("and the level, which is what tells a dead mic from a quiet man",
            line.contains("mic_peak_open=\${deafMic.peakSinceOpen}"))
        for (close in listOf("onFollowUpExpired", "closeCheckInUnanswered")) {
            assertTrue("$close carries it", bodyOf(s, close).contains("heardNothingLine()"))
        }
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
