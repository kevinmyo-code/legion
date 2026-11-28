package com.kevin.legion.service.cascade

import com.kevin.legion.testutil.MainSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * How the call screen is wired into the conversation, checked in the source because the session needs
 * a whole voice stack to run. Each case is one of the slice-1-3 review's findings, and each fails if
 * that finding's fix is taken out again:
 *
 * - F2: the Call button over a running conversation moves it to his ear, never hangs it up.
 * - F4: a call answers before it listens, so the tap's chime and its early capture are the chime
 *   opening's alone.
 * - F10: a call takes its route at the tap, so the proximity lock has the screen dark before the
 *   phone reaches his ear.
 * - F11: a mid-call voice fault ends the call; nothing re-opens one on the loudspeaker.
 */
class CallScreenWiringTest {

    @Test
    fun `the Call button over a running conversation moves it to the ear and never ends it`() {
        val tap = bodyOf(controller(), "onTapCascade")
        val move = tap.indexOf("c.onCallButton()")
        val ends = tap.indexOf("c.onTap()")
        assertTrue("a call press is its own branch", tap.contains("if (call) {") && move >= 0)
        assertTrue("and the tap meaning that ends the chat is the other one", tap.contains("} else {"))
        assertTrue("never both", move < ends)
        val begin = bodyOf(session(), "onCallButton")
        assertTrue("it asks for the earpiece", begin.contains("VoiceOutput.askRoute(earpiece = true)"))
        assertTrue("and draws the call as answered", begin.contains("beginCallLine(answeredAtMs ="))
        assertFalse("it never closes the conversation", begin.contains("close("))
        assertFalse("and never interrupts it", begin.contains("interrupt("))
    }

    @Test
    fun `a call takes its route at the tap, with no chime, no held audio and no capture`() {
        val open = bodyOf(session(), "openNow")
        val chimeOpening = open.indexOf("if (listens && opening == OpeningRule.Opening.CHIME) openMicNow()")
        val callOpening = open.indexOf("if (listens && opening == OpeningRule.Opening.PICKUP) enterRouteNow()")
        assertTrue("the chime opening still opens the mic at the tap", chimeOpening >= 0)
        assertTrue("a call enters its route there instead", callOpening >= 0)
        // Before the turn models, the voice service and the prompt: that is the whole point of F10.
        assertTrue(callOpening < open.indexOf("TurnModels.awaitReady"))
        assertTrue(callOpening < open.indexOf("openBackend(config, started)"))
        val enter = bodyOf(session(), "enterRouteNow")
        assertTrue("it sets the route the turn plan reads", enter.contains("tapRoute = route"))
        assertTrue(enter.contains("tapBarge = barge"))
        assertTrue(enter.contains("enterRoute(barge, \"call\")"))
        assertFalse("no chime and no capture", enter.contains("startMic"))
        assertFalse("and nothing of his is held: the pickup speaks first", enter.contains("MicBacklog"))
    }

    @Test
    fun `the route entered at the tap is not entered a second time when the voice service opens`() {
        val open = bodyOf(session(), "openNow")
        assertTrue(
            "the late entry is skipped for any opening that already took its route",
            open.contains("if (early == null) enterRoute(p.config.bargeIn, b.kind.token)"),
        )
    }

    @Test
    fun `a call never gets the tap's chime, and the guard that keeps it out is pinned`() {
        val open = bodyOf(session(), "openNow")
        // Delete the CHIME guard and every opening gets the earcon in front of the pickup line, with
        // no test failing. This is that guard.
        assertTrue(open.contains("opening == OpeningRule.Opening.CHIME) openMicNow()"))
        assertFalse("not for any opening", open.contains("if (listens) openMicNow()"))
        val chime = bodyOf(session(), "playOpenChime")
        assertTrue("the chime is playback, so the mic never takes it for him", chime.contains("playbackActive = true"))
        assertTrue("and it is the tap's barge-in setting that decides", chime.contains("if (!tapBarge)"))
    }

    @Test
    fun `the pickup is playback too, so barge-in decides whether he is heard over it`() {
        val pickup = bodyOf(session(), "speakPickup")
        assertTrue("it is spoken through the reply speaker", pickup.contains("newSpeaker(null)"))
        assertTrue(pickup.contains("reply.say(line)"))
        // The reply speaker's own first-audio path is what marks playback, so the barge-in gate sees
        // a pickup exactly as it sees a reply: no second rule for a call.
        val audible = bodyOf(session(), "onReplyAudible")
        assertTrue(audible.contains("playbackActive = true"))
        assertTrue("and the gate is told a reply started", audible.contains("it.onReplyStart()"))
        assertTrue("his words over the pickup are the first turn", pickup.contains("openingJob = turnJob"))
        val feed = bodyOf(session(), "onMicBuffer")
        assertTrue("every buffer carries whether something was playing", feed.contains("playbackActive"))
    }

    /**
     * Kevin, 2026-11-28: a call rings about three times before Alfred picks up. It starts once the
     * call's route is entered (so it rings at his ear), it ends before the mic listens and before the
     * pickup speaks, and the release stops it, so a hang-up while it rings is silent at once.
     */
    @Test
    fun `a call rings at its own route before the pickup, and a hang-up silences it`() {
        val open = bodyOf(session(), "openNow")
        val route = open.indexOf("if (listens && opening == OpeningRule.Opening.PICKUP) enterRouteNow()")
        val rings = open.indexOf("ringback.start()")
        assertTrue("it rings", rings >= 0)
        assertTrue("after the route is entered", route in 0 until rings)
        assertTrue("before the turn models and the voice open", rings < open.indexOf("TurnModels.awaitReady"))
        val done = open.indexOf("ringback.awaitDone()")
        assertTrue("the pickup waits for the rings", done > rings)
        assertTrue("Alfred is ready before the wait", open.indexOf("ringback.answerReady()") in rings until done)
        assertTrue("nothing of his is heard while it rings", done < open.indexOf("startMic()"))
        assertTrue("and Alfred answers after it", done < open.indexOf("speakPickup()"))
        assertTrue("the line is fetched during the rings", open.indexOf("pickPickupLine(b)") in rings until done)
        assertTrue(bodyOf(session(), "releaseNow").contains("step(\"ringback\") { ringback.stop() }"))
    }

    @Test
    fun `mute keeps the capture open and feeds nothing, and silence does not hang up on him`() {
        val feed = bodyOf(session(), "onMicBuffer")
        val gate = feed.indexOf("if (micMuted) return")
        assertTrue("muted buffers are dropped", gate >= 0)
        assertTrue("before the clip listener", gate < feed.indexOf("clipListen"))
        assertTrue("and before the turn detector", gate < feed.indexOf("taker.feed"))
        val mute = bodyOf(session(), "setMuted")
        assertFalse("the capture is not closed", mute.contains("micJob"))
        assertTrue("the screen is told", mute.contains("CallLine.muted(callId, on)"))
        // The idle window itself is held rather than its expiry swallowed; its own case below says why.
        assertTrue("a muted call is not closed by silence", bodyOf(session(), "waitOutWindow").contains("micMuted"))
    }

    @Test
    fun `the call screen goes up at the tap and comes down with the call, by id`() {
        val open = bodyOf(session(), "open")
        assertTrue("up before anything opens", open.contains("if (call) beginCallLine()"))
        val release = bodyOf(session(), "releaseNow")
        val ended = release.indexOf("step(\"call_line\") { CallLine.ended(callId) }")
        assertTrue("and down in the release", ended >= 0)
        val begin = bodyOf(session(), "beginCallLine")
        assertTrue("once only", begin.contains("callLineUp.compareAndSet(false, true)"))
        assertTrue("the route readback is what labels the Speaker control", begin.contains("VoiceOutput.live.collect"))
    }

    @Test
    fun `the three controls reach the open conversation through the service`() {
        val service = stripComments(File(sourceRoot(), "service/AriaForegroundService.kt").readText())
        val controller = controller()
        for ((action, call) in CONTROLS) {
            assertTrue("$action is handled", service.contains("intent?.action == $action"))
            assertTrue("$action reaches the controller", service.contains("sessionController.$call"))
        }
        assertTrue(bodyOf(controller, "onCallMute").contains("cascade?.setMuted(on)"))
        assertTrue(bodyOf(controller, "onCallRoute").contains("VoiceOutput.askRoute(earpiece)"))
        val hangUp = bodyOf(controller, "onHangUp")
        assertTrue("hang up ends the conversation", hangUp.contains("close(\"hung_up\", userEnded = true)"))
        val screen = stripComments(File(sourceRoot(), "ui/call/InCallScreen.kt").readText())
        assertTrue("and the screen sends them, holding no session", screen.contains("context.startService("))
        assertFalse(screen.contains("CascadeSession"))
    }

    /**
     * Review F6's wiring, which is what makes the rename fix real: the renamed pool is handed to the
     * voice at construction (membership of the keep set is what lets a line be kept at all) AND
     * warmed on the ordinary filler schedule. Take either away and the pool stays narrowed forever
     * with every pure test still green.
     */
    @Test
    fun `a renamed persona's pickup pool reaches the voice and is warmed`() {
        val s = session()
        assertTrue(
            "handed to the voice at construction",
            s.contains("keepAlso = CallPickup.renamedKeep(CompanionProfile.name(context), ALFRED.defaultName)"),
        )
        // An expression body, so this reads the file rather than a brace-matched body.
        assertTrue("and it widens the keep set", s.contains("keepLines = ScriptedLines.KEEP + keepAlso"))
        val warm = bodyOf(s, "warmFillers")
        assertTrue(
            "and warmed where the fillers are",
            warm.contains("b.tts.warm(CallPickup.renamedKeep(CompanionProfile.name(ctx), ALFRED.defaultName))"),
        )
    }

    /**
     * Mute pauses the idle window rather than swallowing its expiry. Swallowing it is a one-way door:
     * the window is armed by a reply, so after Mute, expiry, Unmute, silence would never close the
     * conversation again, and the mic and the voice session would stay open with nobody closing them.
     */
    @Test
    fun `a muted call holds its idle window and starts it again, never swallows it`() {
        val arm = bodyOf(session(), "armFollowUp")
        assertTrue("the window's wait is the mute-aware one", arm.contains("waitOutWindow(windowMs)"))
        val wait = bodyOf(session(), "waitOutWindow")
        assertTrue("it loops, so the whole window runs again after a hold", wait.contains("while (true)"))
        assertTrue("it holds while muted", wait.contains("while (micMuted && !closing.get())"))
        val expired = bodyOf(session(), "onFollowUpExpired")
        assertFalse("and the expiry itself is never swallowed", expired.contains("micMuted"))
    }

    @Test
    fun `the call screen is mounted, and every touch stops at it`() {
        val activity = stripComments(File(sourceRoot(), "ui/MainActivity.kt").readText())
        assertTrue("drawn over every route", activity.contains("com.kevin.legion.ui.call.InCallOverlay()"))
        val screen = stripComments(File(sourceRoot(), "ui/call/InCallScreen.kt").readText())
        assertTrue("touches are consumed, as the card stage's overlay does", screen.contains("pointerInput(Unit)"))
        assertTrue(screen.contains("changes.forEach { it.consume() }"))
        assertTrue("and a crisis takes the screen back", screen.contains("if (crisis) return"))
    }

    /**
     * Review F11, stated as what is true at this head: Live is retired, so a voice fault mid-call
     * closes the conversation and nothing re-opens one. The branch that WOULD re-open carries neither
     * the call nor the in-hand flag, so whoever un-retires Live owes both, and this fails on the day
     * they flip it without carrying them.
     */
    @Test
    fun `a mid-call voice fault ends the call and never re-opens it on the loudspeaker`() {
        val settings = stripComments(File(sourceRoot(), "service/cascade/VoiceEngineSettings.kt").readText())
        assertTrue("Live is retired", settings.contains("const val LIVE_RETIRED = true"))
        val handOver = bodyOf(session(), "handOver")
        val closes = handOver.indexOf("host.closed(\"voice_failed_\$reason\")")
        assertTrue("a fault closes the conversation", closes >= 0)
        assertTrue("under the retirement flag", handOver.contains("if (VoiceEngineSettings.LIVE_RETIRED) {"))
        val controller = controller()
        val reopen = bodyOf(controller, "openCascade").contains("toLive = { wake, opener ->")
        assertTrue("the only re-open is the retired Live door", reopen)
        assertFalse(
            "and it carries no call, so it must not be un-retired without carrying one",
            controller.contains("startConversation(fromWakeWord = wake, opener = opener, call ="),
        )
    }

    private fun session() = stripComments(MainSource.read(MainSource.CASCADE_SESSION))

    private fun controller() = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())

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

    private companion object {
        val CONTROLS = listOf(
            "ACTION_CALL_MUTE" to "onCallMute(",
            "ACTION_CALL_ROUTE" to "onCallRoute(",
            "ACTION_CALL_HANG_UP" to "onHangUp()",
        )
    }
}
