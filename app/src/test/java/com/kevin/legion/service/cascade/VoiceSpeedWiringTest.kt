@file:Suppress("MaxLineLength", "ReturnCount") // source pins read best on one line; one exit per lookup

package com.kevin.legion.service.cascade

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The speed plan's wiring in [CascadeSession] (Kevin, 2026-11-19, items 1 to 3), read from source as
 * [CascadeWiringTest] does: the session needs a live Context, a mic and a socket to construct. The
 * rules themselves are pure and tested on their own ([OpeningRule], [MicBacklog], [GreetingWords],
 * [FillerTurn], [Backchannel]).
 */
class VoiceSpeedWiringTest {

    // --- 1: a tap opens the mic at once ---------------------------------------------------------

    @Test
    fun `a tap opens the route and the mic before the voice service, and says nothing`() {
        val s = session()
        val openNow = bodyOf(s, "openNow")
        val micNow = openNow.indexOf("openMicNow()")
        assertTrue("a tap opens the mic in openNow", micNow >= 0)
        assertTrue("before the turn models", micNow < openNow.indexOf("TurnModels.awaitReady("))
        assertTrue("before the voice service", micNow < openNow.indexOf("openBackend("))
        // The chime branch returns before the greeting plan is awaited or any line is spoken.
        val chimeAt = openNow.indexOf("listenAfterChime()")
        assertTrue(chimeAt >= 0)
        assertTrue(chimeAt < openNow.indexOf("greetingPlan?.await()"))
        assertTrue(chimeAt < openNow.indexOf("speakGreeting("))
        assertTrue(chimeAt < openNow.indexOf("speakScripted("))
        // No greeting pack is even fetched for a tap.
        assertTrue(openNow.contains("if (opening == OpeningRule.Opening.GREETING)"))
        val micNowBody = bodyOf(s, "openMicNow")
        assertTrue("route first", micNowBody.indexOf("enterRoute(") < micNowBody.indexOf("startMic(chime = true)"))
        assertTrue(micNowBody.indexOf("backlog = MicBacklog()") < micNowBody.indexOf("startMic(chime = true)"))
    }

    @Test
    fun `what he said since the tap reaches the detector first, under the feed lock`() {
        val openNow = bodyOf(session(), "openNow")
        val lock = openNow.indexOf("synchronized(turnLock)")
        val built = openNow.indexOf("turnTaker = taker")
        val drained = openNow.indexOf("drainTo {")
        assertTrue(lock in 0 until built)
        assertTrue(built in 0 until drained)
        assertTrue(openNow.indexOf("backlog = null") > drained)
        // A route entered at the tap is not entered twice.
        assertTrue(openNow.contains("if (early == null) enterRoute("))
        val feed = bodyOf(session(), "onMicBuffer")
        assertTrue(feed.contains("else backlog?.add(buf, n, playbackActive)"))
    }

    @Test
    fun `the chime is local, counted as playback half-duplex, and never a reply`() {
        val s = session()
        val chime = bodyOf(s, "playOpenChime")
        assertTrue(chime.contains("ListenCue.earconPcm()"))
        assertTrue(chime.contains("if (!tapBarge) playbackActive = true"))
        assertTrue(chime.contains("chimeDone.complete(Unit)"))
        for (reply in listOf("onReplyAudible(", "speaking = true", "lastClipText", "history.add", "newSpeaker(", "followUpEvent(")) {
            assertFalse("the chime must not touch $reply", chime.contains(reply))
        }
        // Played once capture has started; the listening window after it, unless he already spoke.
        assertTrue(bodyOf(s, "startMic").contains("onRecording = recording"))
        val after = bodyOf(s, "listenAfterChime")
        assertTrue(after.contains("turnJob = scope.launch(loop)"))
        assertTrue(after.contains("if (!heardSinceOpen) backToListening()"))
        assertTrue(bodyOf(s.substring(s.indexOf("private fun onUserTurnStart()")), "onUserTurnStart").contains("heardSinceOpen = true"))
    }

    @Test
    fun `the tap path is timed from the open call, each step its own LIVE_LATENCY key`() {
        val s = session()
        assertTrue(bodyOf(s, "open").contains("openStartedAtMs = System.currentTimeMillis()"))
        for (key in listOf("open_kind=", "open_mic_ms=", "open_backend_ms=", "open_listening_ms=", "open_backlog_ms=")) {
            assertTrue("openNow no longer logs $key", bodyOf(s, "openNow").contains(key))
        }
        assertTrue(bodyOf(s, "startMic").contains("open_route_ms="))
        assertTrue(bodyOf(s, "onMicBuffer").contains("open_capture_ms="))
        assertTrue(bodyOf(s, "playOpenChime").contains("open_chime_ms="))
    }

    // --- 2: greeting variety ----------------------------------------------------------------------

    @Test
    fun `the hello's avoid list gets the model's whole text, not what had been heard by then`() {
        val greet = bodyOf(session(), "speakGreeting")
        val noted = greet.substring(greet.indexOf("greeting?.noteOpened("))
            .lineSequence().first()
        assertFalse("heard is still empty when the stream ends", noted.contains("reply.spoken()"))
        assertTrue(noted.contains("GreetingWords.of(written.toString())"))
        assertTrue(greet.contains("written.append(delta)"))
        // When it is noted is unchanged (a topic is marked raised at the same point as before).
        assertTrue(greet.indexOf("call.join()") < greet.indexOf("greeting?.noteOpened("))
        assertTrue(greet.indexOf("greeting?.noteOpened(") < greet.indexOf("reply.await()"))
    }

    // --- 3: backchannels and task lines -------------------------------------------------------------

    @Test
    fun `a backchannel is scheduled at the end of his speech and decided by the pure rule`() {
        val s = session()
        val end = bodyOf(s, "onTurnEnd")
        assertTrue(end.indexOf("scheduleBackchannel(u, eotAtMs)") in 0 until end.indexOf("runTurn("))
        val schedule = bodyOf(s, "scheduleBackchannel")
        assertTrue(schedule.contains("Backchannel.decide("))
        assertTrue(schedule.contains("askedBefore = lastTurnAsked"))
        assertTrue(schedule.contains("quiet = QuietMode.isOn(ctx)"))
        assertTrue(schedule.contains("Backchannel.DELAY_MS"))
        // New speech cancels a pending one, and notes whether it started over a reply or a turn.
        val start = bodyOf(s.substring(s.indexOf("private fun onUserTurnStart()")), "onUserTurnStart")
        assertTrue(start.contains("backchannelJob?.cancel()"))
        assertTrue(start.contains("uttOverReply = speaking || turnJob?.isActive == true"))
    }

    @Test
    fun `a backchannel is never a reply and never overlaps the answer`() {
        val s = session()
        val play = bodyOf(s, "playBackchannel")
        for (reply in listOf("speaking = true", "onReplyAudible(", "lastClipText", "history.add", "followUpEvent(", "setCaption(", "audit.assistant(")) {
            assertFalse("a backchannel must not touch $reply", play.contains(reply))
        }
        assertTrue(play.contains("playGate.withLock"))
        assertTrue(play.contains("Backchannel.clipFits("))
        assertTrue(play.contains("audit.filler(\"backchannel\", line)"))
        assertTrue(play.contains("b.tts.inHand("))
        // The answer's writes take the same gate; its first audio marks the turn as answered.
        val speaker = bodyOf(s, "newSpeaker")
        assertTrue(speaker.contains("play = { pcm -> playGate.withLock { p.write(pcm, gen) } }"))
        assertTrue(speaker.contains("turnAudioStarted = true"))
        assertTrue(speaker.contains("onFillerStarted = { text -> audit.filler(\"task\", text) }"))
    }

    @Test
    fun `the task line waits out a backchannel and its spacing`() {
        val s = session()
        val starting = s.substring(s.indexOf("override fun onToolsStarting("))
        val wait = starting.indexOf("awaitBackchannelSpacing()")
        assertTrue(wait >= 0)
        assertTrue(wait < starting.indexOf("sayToolFiller(plan, reply, record)"))
        assertTrue(starting.substring(0, starting.indexOf("override fun onToolResult(")).contains("ToolFillers.stillDue("))
        val spacing = bodyOf(s, "awaitBackchannelSpacing")
        assertTrue(spacing.contains("backchannelJob?.join()"))
        assertTrue(spacing.contains("Backchannel.spacingWaitMs("))
    }

    // --- 4a: speech-to-text hangs ------------------------------------------------------------------

    @Test
    fun `a missed cloud transcript is measured, asked again when voiced, and never said over him`() {
        val s = session()
        val recover = bodyOf(s, "recoverWords")
        assertTrue(recover.contains("return cloudMissed(u, reason, moment)"))
        val missed = bodyOf(s, "cloudMissed")
        assertTrue(missed.contains("SttGuard.voicedMs("))
        assertTrue(missed.contains("MissedLine.onFailure("))
        assertTrue(missed.indexOf("closedOnNoise()") < missed.indexOf("b.stt.begin()"))
        val words = bodyOf(s, "missedWords")
        assertTrue(words.indexOf("MissedLine.hold(") in 0 until words.indexOf("host.notice(MISSED_NOTICE)"))
        assertTrue(bodyOf(s, "afterReply").contains("lastReplyEndAtMs = System.currentTimeMillis()"))
        // Every utterance keeps its audio, so the cloud's miss can be measured and sent again.
        assertTrue(s.contains("TurnUtterance(inner, b, keepAudio = true)"))
    }

    // --- review M1 and follow-ups -------------------------------------------------------------------

    @Test
    fun `half-duplex backchannels keep his audio through the clip and a resume continues the turn`() {
        val s = session()
        val schedule = bodyOf(s, "scheduleBackchannel")
        assertTrue(schedule.contains("halfDuplex = turnTaker?.bargeIn != true"))
        assertTrue(schedule.contains("halfDuplexAllowed = VoiceTurnSettings.backchannelHalfDuplex(ctx)"))
        val play = bodyOf(s, "playBackchannel")
        assertTrue(play.indexOf("listenThroughClip()") in 0 until play.indexOf("playbackActive = true"))
        assertTrue(bodyOf(s, "listenThroughClip").contains("clipListen = ClipListen("))
        val feed = bodyOf(s, "onMicBuffer")
        assertTrue(feed.indexOf("listen.onFrame(buf, n, playbackActive)") in 0 until feed.indexOf("taker.feed("))
        val start = s.substring(s.indexOf("override fun sendActivityStart()"))
        assertTrue(start.indexOf("resumeThroughClip(u)") in start.indexOf("currentUtt = u") until start.indexOf("override fun sendAudio("))
        val resume = bodyOf(s, "resumeThroughClip")
        assertTrue(resume.contains("turn?.writeCalled != true"))
        assertTrue(resume.contains("listen.prefix(TurnTaker.PRE_ROLL_MS"))
        val take = bodyOf(s, "takeOverFirstHalf")
        for (step in listOf("merged = true", "first.cancel()", "turnJob?.cancel()")) assertTrue(step, take.contains(step))
        assertTrue(bodyOf(s.substring(s.indexOf("private fun onUserTurnStart()")), "onUserTurnStart").contains("if (continuesFirstHalf()) return"))
        assertTrue(bodyOf(s, "continuesFirstHalf").contains("takeOverFirstHalf(first)"))
    }

    @Test
    fun `held audio is cleared at release, drafts are not hedged, a clip waits for no answer text`() {
        val s = session()
        val release = bodyOf(s, "releaseNow")
        assertTrue(release.contains("backlog = null"))
        assertTrue(release.contains("clipListen = null"))
        assertTrue(s.contains("gemini.transcribe(audio, hold::add, hedged = false)"))
        assertTrue(bodyOf(s, "playBackchannel").contains("Backchannel.Skip.ANSWER_TEXT"))
        assertTrue(bodyOf(s, "answerTextIn").contains("it.reply.isNotEmpty()"))
    }

    private fun session() = stripComments(File(sourceRoot(), "service/cascade/CascadeSession.kt").readText())

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
