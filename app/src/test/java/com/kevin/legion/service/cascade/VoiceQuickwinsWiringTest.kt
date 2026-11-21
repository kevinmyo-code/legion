package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the dead-air rule ([DeadAir]) and the call pause ([CallPause]) are wired into the session and
 * the controller, read from source: the session needs a live Context, a socket and a mic to construct.
 */
class VoiceQuickwinsWiringTest {

    // --- no dead air -------------------------------------------------------------------------------

    @Test
    fun `the wait line is keyed on the answer's audio and asked clip, never on its first text`() {
        val s = session()
        val state = bodyOf(s, "deadAir")
        assertTrue(state.contains("answerAudioReady = answerAudioStarted"))
        assertTrue(state.contains("answerAskedAtMs = answerAskedAtMs"))
        assertTrue(state.contains("goodbye = turnEndsConversation"))
        assertTrue(state.contains("greetingHeld = greetingJob?.isActive == true"))
        val play = bodyOf(s, "playWaitFiller")
        // Checked again under the gate with the chosen line's length; a line that does not fit is not the end.
        val gate = play.substring(play.indexOf("playGate.withLock"))
        val fit = gate.indexOf("DeadAir.waitLine(deadAir(seq), chosen.ms)")
        assertTrue(fit in 0 until gate.indexOf("turnFillers.incrementAndGet()"))
        assertTrue(gate.contains("return WaitPlay.NOT_YET"))
        assertTrue(play.contains("playPacedWait(p, pcm, b.tts.sampleRate)"))
        // The line can be cut for the answer, and never by a flush (the answer's writes would die with it).
        val paced = bodyOf(s, "playPacedWait")
        assertTrue(paced.contains("PacedClip.play("))
        assertTrue(paced.contains("DeadAir.cutPlaying(answerAudioStarted, left)"))
        assertFalse(paced.contains("flush("))
    }

    @Test
    fun `the answer's clip ask and first audio feed the rule and the bench line`() {
        val s = session()
        val speaker = bodyOf(s, "newSpeaker")
        assertTrue(speaker.contains("onAnswerAsked = { answerAsked(eotAtMs) }"))
        assertTrue(bodyOf(s, "answerAsked").contains("answerAskedAtMs == null"))
        // tts_first_ms counts from the reply's first clip asked, not from that clip's own first audio.
        assertTrue(speaker.contains("answerAudioIn(eotAtMs, now, self?.firstAskedAtMs)"))
        assertFalse(speaker.contains("ttsAskedAtMs"))
        val audio = bodyOf(s, "answerAudioIn")
        assertTrue(audio.indexOf("answerAudioStarted = true") in 0 until audio.indexOf("turnGap?.line(now)"))
        assertTrue(bodyOf(s, "answerGotGate").contains("answer_hold_ms="))
        // Every turn of his starts a fresh timeline and clears the last turn's marks.
        val schedule = bodyOf(s, "scheduleBackchannel")
        for (reset in listOf("answerAskedAtMs = null", "turnEndsConversation = false", "turnGap = TurnGap(eotAtMs)")) {
            assertTrue(reset, schedule.indexOf(reset) in 0 until schedule.indexOf("Backchannel.decide("))
        }
        // Each filler kind marks its own sound.
        assertTrue(bodyOf(s, "playBackchannel").contains("turnGap?.started(TurnGap.BACKCHANNEL"))
        assertTrue(bodyOf(s, "noteTaskFiller").contains("turnGap?.started(TurnGap.TOOL"))
        assertTrue(bodyOf(s, "playPacedWait").contains("turnGap?.started(TurnGap.WAIT"))
    }

    @Test
    fun `a stop marks the turn as ending the conversation before anything else is said`() {
        val stop = bodyOf(session(), "endByStop")
        assertTrue(stop.indexOf("turnEndsConversation = true") in 0 until stop.indexOf("endByVoice()"))
        assertTrue(bodyOf(session(), "speakGreeting").contains("greetingJob = job"))
    }

    // --- pause on a call ---------------------------------------------------------------------------

    @Test
    fun `a call closes the conversation quietly, the engine's turn released, nothing more heard`() {
        val s = session()
        val pause = bodyOf(s, "pauseForCall")
        val order = listOf(
            "callPaused = true",
            "CallPause.line(reason)",
            "fillerJobs.endTurn()",
            "speaker?.interrupt()",
            "player?.flush()",
            "currentUtt?.engine?.release()",
            "shutdown(CallPause.closeReason(reason))",
            "host.closed(CallPause.closeReason(reason))",
        )
        val at = order.map { pause.indexOf(it) }
        assertTrue("$at", at.all { it >= 0 } && at == at.sorted())
        // No words: nothing is said or owed-said on the way out.
        for (word in listOf("sayOwed(", "sayFarewell(", "newSpeaker(", "host.notice(")) {
            assertFalse(word, pause.contains(word))
        }
        // From the moment it fires, no buffer reaches any speech-to-text.
        val feed = bodyOf(s, "onMicBuffer")
        assertTrue(feed.indexOf("if (callPaused) return") in 0 until feed.indexOf("taker.feed("))
    }

    @Test
    fun `the watch runs with the mic, knows our capture, hears focus, and stops at release`() {
        val s = session()
        val mic = bodyOf(s, "startMic")
        // Only once the route is in (review C1): a Bluetooth enter is never read as someone's call.
        val watch = mic.indexOf("callWatch.start(scope)")
        assertTrue(watch in mic.indexOf("audioRoute.awaitBluetoothMic()") until mic.indexOf("CascadeMic(ctx).run("))
        assertTrue(mic.contains("callWatch.ourCapture(id)"))
        assertTrue(bodyOf(s, "requestFocus").contains("setOnAudioFocusChangeListener(callWatch.focusListener)"))
        assertEquals(1, Regex("callWatch\\.stop\\(\\)").findAll(bodyOf(s, "releaseNow")).count())
        assertTrue(s.contains("CallWatch(ctx, audioManager) { reason -> scope.launch(loop) { pauseForCall(reason) } }"))
    }

    @Test
    fun `call audio is marked ours before the mode changes, on every enter (C1)`() {
        val mode = stripComments(File(sourceRoot(), "service/turn/CallAudioMode.kt").readText())
        for (enter in listOf("enter", "enterBluetooth")) {
            val body = bodyOf(mode, enter)
            val mark = body.indexOf("ownCommActive = true")
            assertTrue(enter, mark in 0 until body.indexOf("audio.mode = AudioManager.MODE_IN_COMMUNICATION"))
        }
    }

    @Test
    fun `every door of the controller refuses on any call, VoIP included`() {
        val c = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())
        assertFalse("the cellular-only check is gone from the doors", c.contains("TelephonyController.isInCall"))
        assertEquals(5, Regex("CallWatch\\.isInCallNow\\(appContext\\)").findAll(c).count())
        val onTap = bodyOf(c, "onTap")
        assertTrue(onTap.indexOf("CallWatch.isInCallNow(appContext)") in 0 until onTap.indexOf("openCascade("))
        val watch = stripComments(File(sourceRoot(), "service/cascade/CallWatch.kt").readText())
        val door = watch.substring(watch.indexOf("fun isInCallNow("))
        assertTrue(door.contains("CallPause.blocksOpen(signals(ctx,"))
    }

    private fun session() = stripComments(File(sourceRoot(), "service/cascade/CascadeSession.kt").readText())

    private fun bodyOf(source: String, name: String): String {
        val signature = Regex("fun $name\\(").find(source)?.range?.first ?: error("no fun $name(")
        val open = source.indexOf('{', signature)
        val eq = source.indexOf('=', signature)
        // An expression body (`fun x(): T = ...`): the rest of that statement.
        val expression = eq in (signature + 1) until open && source.substring(eq, open).lines().size > 1
        return if (expression) {
            source.substring(eq, source.indexOf("\n\n", eq).let { if (it < 0) source.length else it })
        } else {
            blockFrom(source, open)
        }
    }

    private fun blockFrom(source: String, open: Int): String {
        var depth = 0
        var i = open
        var end = -1
        while (end < 0 && i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) end = i
            }
            i++
        }
        assertTrue("unbalanced braces", end > open)
        return source.substring(open + 1, end)
    }

    private fun sourceRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "app/$SRC"), File(it, SRC)) }
            .first { it.isDirectory }

    private fun stripComments(text: String): String =
        LINE_COMMENT.replace(BLOCK_COMMENT.replace(text) { "\n".repeat(it.value.count { c -> c == '\n' }) }, "")

    private companion object {
        const val SRC = "src/main/java/com/kevin/legion"
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("(?<![:\"])//[^\n]*")
    }
}
