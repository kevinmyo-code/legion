@file:Suppress("MaxLineLength", "ReturnCount", "EmptyFunctionBlock") // test fixtures: invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.service.Phase
import com.kevin.legion.testutil.MainSource
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
        // Two doors open a conversation: a tap or wake word (onTap), and the adb test door
        // (openTestCascade, service/testturn/), which follows the same setting after its own guards.
        assertEquals("read once per door in the controller", 2, Regex("VoiceEngineSettings\\.engine\\(").findAll(controller).count())
        val testDoor = bodyOf(controller, "openTestCascade")
        val testReadAt = testDoor.indexOf("VoiceEngineSettings.engine(")
        assertTrue("the test door reads it", testReadAt >= 0)
        for (guard in listOf("isOnline()", "hasKey()", "isInCall")) {
            assertTrue("$guard must come before the test door's engine choice", testDoor.indexOf(guard) in 0 until testReadAt)
        }
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
        for (step in listOf("cancelAndJoin()", "player?.release()", "exit(", "abandonFocus()", "micHold.drop()", "backend?.close(")) {
            assertTrue("releaseNow() no longer does $step", release.contains(step))
        }
    }

    // --- the cloud fallback (Kevin, 2026-11-15) -------------------------------------------------

    @Test
    fun `a laptop that cannot open hands the conversation to the cloud voice, Live only if that cannot start`() {
        val open = bodyOf(session(), "openBackend")
        val routed = open.indexOf("CascadeRules.nextAfter(SpeechBackend.Kind.LAPTOP")
        assertTrue(routed >= 0)
        assertTrue("the cloud voice is built after the laptop refusal", open.indexOf("cloudSpeech(scope") > routed)
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
    fun `a cloud voice that fails on a reply keeps the conversation, from memory or the earcon`() {
        val s = session()
        val answer = bodyOf(s, "answerTurn")
        assertTrue("a failed cloud reply says it lost its voice", answer.contains("sayVoiceLost("))
        assertFalse("a failed reply never hands over, which closes", answer.contains("\"tts\""))
        assertTrue("the answer still reaches the record and the panel", answer.indexOf("unsaid()") in 0 until answer.indexOf("sayVoiceLost("))
        val lost = bodyOf(s, "sayVoiceLost")
        assertTrue("a kept line only when it is in hand", lost.contains("inHand("))
        assertTrue("else the earcon", lost.contains("earconPcm()"))
        assertTrue("the earcon is playback, never a turn", lost.indexOf("onReplyAudible()") in 0 until lost.indexOf("earconPcm()"))
        assertTrue("the window opens again", lost.contains("afterReply()"))
        for (closer in listOf("closingTone", "shutdown(", "handOver(")) assertFalse(closer, lost.contains(closer))
        assertTrue("a scripted line on the cloud voice too", bodyOf(s, "onScriptedFailed").contains("sayVoiceLost("))
        assertTrue("warmed with the fillers", ScriptedLines.VOICE_LOST in ScriptedLines.KEEP)
        assertTrue(s.contains("ScriptedLines.MISSED + ScriptedLines.VOICE_LOST"))
    }

    @Test
    fun `a stop is checked before every noise guard and never restarts the waiting request`() {
        val s = session()
        val run = bodyOf(s, "runTurn")
        val stop = run.indexOf("val stop = stopWords && SttGuard.stopVoiced(stt.text, stt.voicedMs)")
        assertTrue("a stop over no voice is not a stop", stop >= 0)
        assertTrue("decided before the transcript guard can drop it", stop < run.indexOf("if (stop) return endByStop(u, stt.text)"))
        assertTrue("every turn logs voiced time and the decision", run.contains("stt_voiced ms="))
        // 2026-11-23, goodbye hardening: every whole-turn farewell decision is in LIVE_AUDIT, matched or not.
        // Blind review of 56e9404e, finding 1: the line logged the RAW transcript, so a word the guard
        // had stripped (a script none of his languages use) rode into the log on the very line whose
        // subject says it never does. It logs what the guard kept.
        assertTrue("the decision is audited", run.contains("stopWords, stop, stt.voicedMs, v is SttGuard.Verdict.Keep,"))
        assertTrue("with the words the guard kept", run.contains("(v as? SttGuard.Verdict.Keep)?.text ?: stt.text,"))
        assertFalse("never the raw transcript", run.contains("noteGoodbye(stt.text,"))
        assertTrue("a recovered transcript too", run.contains("noteGoodbye(recovered, recoveredStop,"))
        assertTrue("never words the guard threw away", bodyOf(s, "noteGoodbye").contains("if (!kept && !matched) return"))
        assertTrue("with the words the user line already logs", bodyOf(s, "noteGoodbye").contains("audit.goodbye("))
        val dropped = run.indexOf("if (stopWords && !stop) {")
        assertTrue("a stop dropped as noise discards the waiting request", dropped >= 0 && run.indexOf("carry = \"\"", dropped) in dropped until run.indexOf("val v = verdictOf(stt)"))
        assertTrue("the clear-codes gate gets the clip's voice", s.contains("ClearCodesVoiceGate.HeardVoice(record.voicedMs)"))
        assertTrue("recovered words too", run.contains("endsConversation(recovered)"))
        val end = bodyOf(s, "endByStop")
        assertTrue("the waiting request is dropped, not answered", end.contains("carry = \"\""))
        assertTrue(end.contains("endByVoice()"))
        assertFalse(end.contains("answerTurn("))
        // Blind review of 56e9404e, finding 4: the stop rule turned on the last clip ending in "?"
        // alone, so a pending confirmation whose read-back is a statement let "yes, that's it" close
        // in code, before the model and before the write guard. A pending ask is its own signal now,
        // and reaches the rule as a parameter of its own; the rule answers both the same way.
        val ends = bodyOf(s, "endsConversation")
        assertTrue("the last clip asked", ends.contains("afterQuestion = FollowUpWindow.isQuestion(lastClipText)"))
        assertTrue("a pending ask reaches the rule too", ends.contains("draftPending = draftPending()"))
        val draft = bodyOf(s, "draftPending")
        assertTrue("his last turn asked", draft.contains("lastTurnAsked"))
        assertTrue("the turn in hand asked too", draft.contains("turn?.asked == true"))
        assertTrue("the log line says an answer was awaited", bodyOf(s, "noteGoodbye").contains("afterQuestion = awaitingAnswer()"))
        assertTrue("either signal", bodyOf(s, "awaitingAnswer").contains("isQuestion(lastClipText) || draftPending()"))
        // A zero-voice "Bye." is noise for the guard, which counts it toward the cap.
        assertTrue(SttGuard.verdict("Bye.", 0L) is SttGuard.Verdict.Drop)
    }

    @Test
    fun `silent noise turns are capped like spoken misses, five in a row close with the tone`() {
        val s = session()
        val close = bodyOf(s, "closedOnNoise")
        assertTrue(close.contains("MAX_NOISE_IN_A_ROW"))
        // 2026-11-20: the close's chime is played by the release (playCloseCue), not a stock tone here.
        assertTrue(bodyOf(s, "releaseNow").contains("playCloseCue(reason)"))
        // F4, 2026-11-21: a farewell ends the acknowledgement, and cuts it if it is playing ([playAckClip]).
        val stop = bodyOf(s, "endByStop")
        assertTrue(stop.indexOf("cutAck()") in 0 until stop.indexOf("endByVoice()"))
        assertFalse(stop.contains("if (!playbackActive) fillerJobs.endTurn()"))
        // F2: the hand-over's release carries the reason the chime rule knows.
        assertTrue(bodyOf(s, "handOver").contains("release(CloseCue.voiceFailedReason(reason, VoiceEngineSettings.LIVE_RETIRED, quiet))"))
        assertTrue("a noise drop counts", bodyOf(s, "droppedWords").contains("closedOnNoise()"))
        assertTrue("no voice at all counts", bodyOf(s, "runTurn").contains("closedOnNoise()"))
        assertTrue("heard words reset it", bodyOf(s, "runTurn").contains("noiseInARow = 0"))
        assertEquals(5, CascadeSession.MAX_NOISE_IN_A_ROW)
    }

    @Test
    fun `every tool that takes a date gets his day word, at the one dispatch door, before any toolbox`() {
        val toolbox = stripComments(MainSource.read(MainSource.LIVE_TOOLBOX))
        val dispatch = bodyOf(toolbox, "dispatch")
        val fix = dispatch.indexOf("DateCarry.resolved(name, args, userText, it)")
        assertTrue(fix >= 0)
        assertTrue("before the first toolbox and the big when", fix < dispatch.indexOf("EngineToolbox.dispatch(") && fix < dispatch.indexOf("return when (name)"))
        assertTrue("the result carries the note", dispatch.contains("put(\"date_note\", note)"))
        assertEquals("one path: no tool corrects on its own", 1, Regex("(RelativeDay\\.corrected|DateCarry\\.resolved)\\(").findAll(toolbox).count())
        assertTrue("only a voice turn's context carries the day", dispatch.contains("coroutineContext[RelativeDay.TurnDates]"))
        val s = session()
        assertTrue("the session gives the prompt's day and the turn's count", s.contains("RelativeDay.TurnDates(promptDay, record.startedAt, record.dateCalls <= 1, conversationDay.day)"))
        assertTrue("counted before the round runs", s.contains("RelativeDay.isDateCall("))
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
        assertTrue(bodyOf(s, "startMic").contains("onMicBuffer(buf, n)"))
        val feed = bodyOf(s, "onMicBuffer")
        assertTrue(feed.contains("synchronized(turnLock)"))
        assertTrue(feed.contains("taker.feed(buf, n, playbackActive)"))
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
    fun `the opening line never waits for the prompt or the cache, 11-15 phone logs showed 9 s`() {
        val s = session()
        val openNow = bodyOf(s, "openNow")
        for (blocking in listOf("awaitPrompt(", "prefixReady", ".await()\n        prefix", "cache.warm(", "buildLiveContext(")) {
            assertFalse("openNow must not wait on $blocking", openNow.contains(blocking))
        }
        val build = openNow.indexOf("buildPrompt(")
        assertTrue(build >= 0)
        assertTrue("the prompt starts building before the mic and the line", build < openNow.indexOf("startMic()"))
        assertTrue(openNow.indexOf("speakScripted(") > openNow.indexOf("startMic()"))
        val prompt = bodyOf(s, "buildPrompt")
        for (phase in listOf("open_base_ms=", "open_declarations_ms=", "open_live_context_ms=", "open_prefix_ready_ms=")) {
            assertTrue("buildPrompt no longer logs $phase", prompt.contains(phase))
        }
        // The cache create waits on the cacheable prefix only, never on the live context.
        val prefixPart = prompt.substring(prompt.indexOf("prefixReady ="), prompt.indexOf("preambleReady ="))
        assertTrue(prefixPart.contains("cache.warm("))
        assertFalse(prefixPart.contains("buildLiveContext("))
        // The first turn and the greeting wait for it instead.
        assertTrue(bodyOf(s, "answerTurn").contains("awaitPrompt()"))
        assertTrue(bodyOf(s, "speakGreeting").contains("awaitPrompt(liveContextWaitMs = 0)"))
        // A cancel while the prompt builds clears the turn and keeps his words.
        val answer = bodyOf(s, "answerTurn")
        val wait = answer.substring(0, answer.indexOf("?: run {"))
        assertTrue(wait.contains("catch (e: CancellationException)"))
        assertTrue(wait.contains("turn = null"))
        assertTrue(Regex("catch \\(e: CancellationException\\) \\{\\s+throw e").containsMatchIn(bodyOf(s, "awaitPrompt")))
    }

    // --- speculative answers (voice-fast, 2026-11-15) ----------------------------------------------

    @Test
    fun `a draft becomes the answer only after the turn is confirmed and checked like any turn`() {
        val s = session()
        val run = bodyOf(s, "runTurn")
        val take = run.indexOf("takeDraft(u, heard)")
        assertTrue(take >= 0)
        for (check in listOf("CrisisDetector.detect(heard)", "endsConversation(stt.text)", "heard.isBlank()")) {
            assertTrue("$check must come before a draft is taken", run.indexOf(check) in 0 until take)
        }
        assertTrue(bodyOf(s, "answerTurn").contains("drafted?.adopt(executorFor(record, seq), listenerFor(record, reply))"))
        // The draft path never speaks and never reaches a tool runner: both come only with adopt().
        for (name in listOf("onMaybeEnd", "startDraftAnswer", "watchCloudSilence", "startCloudDraft", "onPartialText")) {
            val body = bodyOf(s, name)
            for (forbidden in listOf("newSpeaker(", "executorFor(", "host.runTool(", "listenerFor(", "playCue(")) {
                assertFalse("$name must not call $forbidden", body.contains(forbidden))
            }
        }
        assertTrue(bodyOf(s, "draftable").contains("CrisisDetector.detect(heard)"))
        assertTrue(bodyOf(s, "releaseNow").contains("discardDraft(\"closed\")"))
        assertTrue(bodyOf(s, "swapToCloud").contains("discardDraft("))
    }

    // --- voice quick wins (2026-11-16) -------------------------------------------------------------

    @Test
    fun `his turns carry the agenda, the greeting does not, and a write withdraws it`() {
        val s = session()
        assertTrue(bodyOf(s, "answerTurn").contains("turnContentsFor(heard)"))
        assertTrue(bodyOf(s, "startDraftAnswer").contains("turnContentsFor(heard)"))
        assertFalse(bodyOf(s, "speakGreeting").contains("turnContentsFor("))
        assertTrue(bodyOf(s, "loadAgenda").contains("if (test != null || opener != null) return \"\""))
        // Fetched beside the live context, never on the open path or in the cached prefix.
        assertFalse(bodyOf(s, "openNow").contains("loadAgenda("))
        val prompt = bodyOf(s, "buildPrompt")
        assertTrue(prompt.substring(prompt.indexOf("preambleReady =")).contains("loadAgenda()"))
        assertFalse(prompt.substring(prompt.indexOf("prefixReady ="), prompt.indexOf("preambleReady =")).contains("agenda"))
        val result = bodyOf(s, "onToolResult")
        assertTrue(result.contains("val write = CascadeRules.isWrite(name, args)"))
        assertTrue("a write withdraws it", result.indexOf("noteChange(response)") > result.indexOf("if (write) {"))
    }

    @Test
    fun `the model's own leading filler is dropped where speech and the transcript meet`() {
        val s = session()
        assertTrue(bodyOf(s, "newSpeaker").contains("onFillerDropped = { LiveLatency.log(LiveLatency.TAG, \"reply_filler_dropped n=1\") }"))
        val answer = bodyOf(s, "answerTurn")
        assertTrue(answer.contains("reply.spoken().ifBlank { LeadingFiller.strip(result.text) }"))
        // Review F1: the re-voice says the model's words only; code's write line is owed and said first.
        assertTrue(answer.contains("text ?: LeadingFiller.strip(modelText(record))"))
    }

    /**
     * Kevin, 2026-11-23: an unprompted check-in that opens a voice turn stays a single turn. Read
     * from source, as the whole file's wiring tests are: the session needs a Context, a mic and a
     * socket to construct.
     */
    @Test
    fun `an answered check-in closes after its one reply and opens no second window`() {
        val s = session()
        // He speaks: the check-in is answered, not finished, because its reply is still owed. Read by
        // offset, not bodyOf: the mic sink has an onUserTurnStart of its own that calls this one.
        val spokeAt = s.indexOf("private fun onUserTurnStart()")
        val answeredAt = s.indexOf("checkInStep = CheckInStep.ANSWERED")
        val reportedAt = s.indexOf("reportCheckIn(CheckInOutcome.ANSWERED)")
        val nextFunAt = s.indexOf("private fun ", spokeAt + 1)
        assertTrue("his speech is the one place the check-in becomes ANSWERED", spokeAt >= 0 && nextFunAt > spokeAt)
        assertTrue("$spokeAt $answeredAt $reportedAt $nextFunAt", answeredAt in spokeAt until nextFunAt)
        assertTrue("$spokeAt $reportedAt $nextFunAt", reportedAt in spokeAt until nextFunAt)
        assertEquals("ANSWERED is set once, where he speaks", 1, s.split("checkInStep = CheckInStep.ANSWERED").size - 1)
        // That answer's reply played: the one exchange is over.
        val answer = bodyOf(s, "answerTurn")
        assertTrue(
            answer.contains("if (record.fromUser && checkInStep == CheckInStep.ANSWERED) checkInStep = CheckInStep.REPLIED"),
        )
        // So the close decision sees it, and closes instead of listening again.
        val after = bodyOf(s, "afterReply")
        assertTrue(after.contains("checkInReplied = checkInStep == CheckInStep.REPLIED"))
        val branch = after.substringAfter("CascadeRules.AfterReply.CHECK_IN_ANSWERED ->", "")
        assertTrue("the answered check-in has its own close branch", branch.isNotBlank())
        val close = branch.substringBefore("CascadeRules.AfterReply.LISTEN")
        assertTrue(close.contains("shutdown(\"check_in_answered\")"))
        assertTrue(close.contains("host.closed(\"check_in_answered\")"))
        assertFalse("a closed check-in never goes back to listening", close.contains("backToListening()"))
        assertEquals(
            CascadeRules.AfterReply.CHECK_IN_ANSWERED,
            CascadeRules.afterReply(listens = true, dismissed = false, oneShotDone = false, checkInReplied = true),
        )
        // The line he never answered keeps its own window and its own scripted closer.
        val line = bodyOf(s, "afterCheckInLine")
        assertFalse(line.contains("CheckInStep.REPLIED"))
        assertTrue(line.contains("checkInStep = CheckInStep.LISTENING"))
        assertTrue(bodyOf(s, "onFollowUpExpired").contains("if (checkInStep == CheckInStep.LISTENING)"))
        assertTrue(bodyOf(s, "closeCheckInUnanswered").contains("reportCheckIn(CheckInOutcome.NO_REPLY)"))
    }

    @Test
    fun `a check-in conversation carries no agenda block on any turn`() {
        val s = session()
        // A check-in opens its own conversation with an opener (CascadeOpener.checkIn set) ...
        assertTrue(s.contains("private var checkInStep = if (opener?.checkIn != null) CheckInStep.LINE else CheckInStep.NONE"))
        assertTrue(File(sourceRoot(), "service/LiveSessionController.kt").readText().contains("checkIn = checkIn?.takeIf { listen },"))
        // ... so the read at open gives "", and every later turn sees "" (never the unavailable line).
        assertTrue(bodyOf(s, "loadAgenda").contains("if (test != null || opener != null) return \"\""))
        val now = 1_800_000_000_000L
        val zone = java.time.ZoneId.of("UTC")
        for (changed in listOf(null, java.time.LocalTime.NOON)) {
            for (at in listOf(now, now + 86_400_000L)) {
                assertEquals("", AgendaBlock.current("", 0L, changed, at, zone))
            }
        }
        assertEquals("PRE.", CascadePrompt.withAgenda("PRE.", ""))
        // His reply turns in that conversation and the check-in line both build from turnPreamble().
        assertTrue(bodyOf(s, "turnPreamble").contains("CascadePrompt.withAgenda(preamble, agendaNow())"))
    }

    @Test
    fun `a tool turn that ended on filler is counted, and a second filler says the failure line`() {
        val answer = bodyOf(session(), "answerTurn")
        assertTrue(answer.contains("\"reply_filler_continued n=1\""))
        assertTrue(answer.contains("\"reply_filler_again n=1\""))
        assertTrue(answer.contains("val sayFailure = !reply.anyText || result.fillerAgain"))
    }

    @Test
    fun `the agenda is the tool's own read of today, bounded, and ages out at turn time`() {
        val s = session()
        // One source of truth (2026-11-16): the block reads what day read_calendar reads, no pack.
        val load = bodyOf(s, "loadAgenda")
        assertTrue(load.contains("withTimeoutOrNull(AGENDA_WAIT_MS) { CalendarWindowRead.today(ctx, zone, nowMs) }"))
        assertFalse(load.contains("GreetingFactory"))
        val toolbox = MainSource.read(MainSource.LIVE_TOOLBOX)
        assertTrue(toolbox.contains("com.kevin.legion.calendar.CalendarWindowRead.read(context, startMs, endMs, zone, nowMs)"))
        // The 30-minute and other-day rule is checked for every turn, not only at open.
        assertTrue(bodyOf(s, "agendaNow").contains("AgendaBlock.current("))
        // The greeting never waits for the agenda: it does not wait for the preamble at all.
        assertTrue(s.contains("awaitPrompt(liveContextWaitMs = 0)"))
    }

    @Test
    fun `writes are judged on the tool a call stands for, and marked before they run`() {
        val s = session()
        assertTrue(bodyOf(s, "newTextLoop").contains("isMutating = CascadeRules::isWrite"))
        val exec = bodyOf(s, "executorFor")
        assertTrue(exec.indexOf("claimWrite(record, name)") in 0 until exec.indexOf("runDated("))
        assertTrue(bodyOf(s, "runDated").contains("host.runTool("))
        assertTrue(bodyOf(s, "claimWrite").contains("record.writeCalled = true"))
    }

    // Review of 206a8898: a resume taking the turn over and a write starting in it are decided under one
    // lock, so exactly one wins: a claimed turn runs no write, a turn whose write started is not claimed.
    @Test
    fun `a write and a resume takeover are decided atomically under the turn lock`() {
        val s = session()
        val exec = bodyOf(s, "executorFor")
        assertTrue(exec.contains("Superseded by his next words: not run."))
        val claim = bodyOf(s, "claimWrite")
        val lock = claim.indexOf("synchronized(turnLock)")
        assertTrue(lock >= 0)
        assertTrue(claim.indexOf("record.resumeClaimed") in lock until claim.indexOf("record.writeCalled = true"))
        // The lock is never held while the write runs: the write is outside claimWrite.
        assertFalse(claim.contains("host.runTool("))
        assertTrue(bodyOf(s, "resumeThroughClip").contains("turn?.resumeClaimed = true"))
    }

    @Test
    fun `speech before a tool turn's reply is merged or interrupted, never queued behind it`() {
        val s = session()
        assertTrue(s.contains("CascadeRules.newSpeech("))
        assertTrue(s.contains("CascadeRules.NewSpeech.INTERRUPT -> interrupt(\"speech_during_tools\")"))
        // A merged turn's words carry; it is not also recorded on its own.
        assertTrue(bodyOf(s, "answerTurn").contains("if (!record.merged && (carry.isBlank() || record.toolsRan.isNotEmpty()))"))
        // The end of turn itself is unchanged: a new turn still waits for the one before it.
        assertTrue(bodyOf(s, "onTurnEnd").contains("previous?.join()"))
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

    // --- ADR 0090 on the phone (2026-11-17) -------------------------------------------------------

    @Test
    fun `the one write guard runs before any resolved tool, on every path`() {
        val c = controller()
        val runTool = bodyOf(c, "runTool")
        // The crisis argument joined it for #462; the pin is the guard call, not its argument list.
        val guardAt = runTool.indexOf("ReadThroughWriteGuard")
        assertTrue("runTool asks the guard", guardAt >= 0)
        assertTrue("before anything runs", guardAt < runTool.indexOf("runResolvedTool("))
        // runTool is the only way in: one runResolvedTool caller, one dispatch caller.
        assertEquals(2, Regex.fromLiteral("runResolvedTool(").findAll(c).count()) // the call and the definition
        assertEquals(1, Regex.fromLiteral("LiveToolbox.dispatch(").findAll(c).count())
        assertTrue(bodyOf(c, "runToolBounded").contains("runTool(name, args, readThrough, userText)"))
    }

    @Test
    fun `Live hands a read-through tool the mark without its own`() {
        assertTrue(controller().contains("s.readThroughToolTouchedThisTurn() && !call.ownMarkOnly"))
    }

    @Test
    fun `the audit row is judged and named as the tool the call stands for`() {
        val audit = bodyOf(controller(), "auditToolCall")
        val resolveAt = audit.indexOf("LiveSurface.resolve(wireName, wireArgs)")
        assertTrue(resolveAt >= 0)
        assertTrue(resolveAt < audit.indexOf("isEpisodicExcludedTool(name)"))
    }

    @Test
    fun `the cascade marks on the resolved call and keeps an opener or carry mark`() {
        val s = session()
        assertTrue(s.contains("isReadThrough = CascadeRules::isReadThrough,"))
        val exec = bodyOf(s, "executorFor")
        // Logged under the called name with its args; LiveAudit redacts on the resolved call (LiveAuditTest).
        assertTrue(exec.contains("audit.toolCall(id, name, false, args.toString())"))
        assertTrue(bodyOf(s, "runDated").contains("readThrough || record.startMarked,"))
        assertTrue(bodyOf(s, "recordTurn").contains("if (fromUser && said.isNotBlank()) readThroughCarry.answered()"))
        val answer = bodyOf(s, "answerTurn")
        val carryAt = answer.indexOf("readThroughCarry.startTurn(record.fromUser, record.startMarked)")
        assertTrue("the carry is taken as a turn starts", carryAt >= 0)
        assertTrue("before the model runs", carryAt < answer.indexOf("textLoop!!.answer("))
        assertTrue(bodyOf(s, "promptRecord").contains("it.startMarked = o.readThrough"))
    }

    // --- source access ----------------------------------------------------------------------------

    @Test
    fun `a one-word answer to the spoken which-car ask reaches the lone-word guard as an answer`() {
        val executor = bodyOf(session(), "executorFor")
        val guard = executor.indexOf("LoneWordWriteGuard.refusal(")
        assertTrue("the guard runs in the executor", guard >= 0)
        assertTrue(
            "the spoken which-car ask counts as asked (seam d)",
            executor.substring(0, guard).contains("val asked = record.askedBefore || answersSpokenAsk(") &&
                executor.substring(guard).substringBefore("\n").contains("asked)"),
        )
        assertTrue(bodyOf(session(), "answersSpokenAsk").contains("SpokenWhichCarAsk.answers("))
    }

    private fun controller() = stripComments(File(sourceRoot(), "service/LiveSessionController.kt").readText())

    @Test
    fun `the greeting is held whole and gated before any of it is spoken`() {
        val g = bodyOf(session(), "speakGreeting")
        // Nothing streams to the speaker as it arrives.
        assertFalse(g.contains("reply.text(delta)"))
        val join = g.indexOf("GreetingBudget.WHOLE_LINE_MS")
        val gate = g.indexOf("greetingGate(whole, written.toString(), plan.name)")
        val speak = g.indexOf("reply.text(written.toString())")
        val kept = g.indexOf("noteOpened(plan, GreetingWords.of(written.toString(), plan.name))")
        assertTrue("$join $gate $speak $kept", join in 0 until gate && gate < speak && speak < kept)
        // Any gate but SPEAK says nothing of the model's line and keeps nothing; only some play the scripted hello.
        val blocked = g.substring(gate, speak)
        assertTrue(blocked.contains("greetingNotSpoken(gate)"))
        assertTrue(blocked.contains("return@launch"))
        val notSpoken = bodyOf(session(), "greetingNotSpoken")
        assertTrue(notSpoken.contains("if (gate.scripted) speakScripted(ackLines.next())"))
        assertTrue(notSpoken.contains("gate.logLine(earcon)?.let"))
        assertFalse(blocked.contains("noteOpened("))
        // The gate reads his speech the moment it decides, and the name check gets his name.
        val decide = bodyOf(session(), "greetingGate")
        assertTrue(decide.contains("GreetingGate.decide(heardSinceOpen, whole, whole && GreetingWords.namesHim(written, name))"))
    }

    @Test
    fun `one closing sound, the chime, and the old ACK tone is gone from every path`() {
        val root = sourceRoot()
        // The cascade never reaches the old tone: no host hook, no bridge parameter.
        assertFalse("CascadeSession", stripComments(MainSource.read(MainSource.CASCADE_SESSION)).contains("closingTone"))
        listOf("service/cascade/CascadeBridge.kt", "service/cascade/CascadeHost.kt")
            .map { File(root, it) }.filter { it.isFile }
            .forEach { f -> assertFalse(f.name, stripComments(f.readText()).contains("closingTone")) }
        val live = stripComments(File(root, "service/LiveSessionController.kt").readText())
        assertFalse(live.contains("ToneGenerator"))
        assertFalse(live.contains("TONE_PROP_ACK"))
        assertFalse(live.contains("playClosingTone"))
        assertFalse(live.contains("tone = ::"))
        // The Live path (retired, a rollback only) plays the same chime, once, on its window's lapse.
        assertTrue(bodyOf(live, "playCloseChime").contains("ListenCue.closeChimePcm()"))
        assertEquals(1, Regex("playCloseChime\\(\\)").findAll(live).count() - 1)
        // The cascade's chime plays from the one release every close goes through, guarded to once.
        val release = bodyOf(session(), "releaseNow")
        assertEquals(1, Regex("playCloseCue\\(").findAll(release).count())
    }

    @Test
    fun `a dropped greeting acknowledges the wake with the earcon unless he is talking now`() {
        val g = bodyOf(session(), "speakGreeting")
        val blocked = g.substring(g.indexOf("if (gate != GreetingGate.SPEAK)"), g.indexOf("reply.text(written.toString())"))
        assertTrue(blocked.indexOf("greetingNotSpoken(gate)") in 0 until blocked.indexOf("return@launch"))
        val gate = bodyOf(session(), "greetingNotSpoken")
        assertTrue(gate.contains("GreetingGate.earconAfterDrop(System.currentTimeMillis(), lastLocalSpeechAtMs)"))
        assertTrue(gate.contains("gate.logLine(earcon)"))
        assertTrue(gate.contains("if (earcon) playDropEarcon()"))
        assertTrue(bodyOf(session(), "playDropEarcon").contains("ListenCue.earconPcm()"))
    }

    @Test
    fun `a stop mid-reply flushes the buffered speech before the chime, and the chime itself is not flushed`() {
        val release = bodyOf(session(), "releaseNow")
        val interrupt = release.indexOf("speaker?.interrupt()")
        val flush = release.indexOf("player?.flush()")
        val chime = release.indexOf("playCloseCue(reason)")
        assertTrue("$interrupt $flush $chime", interrupt in 0 until flush && flush < chime)
        assertEquals(1, release.split("player?.flush()").size - 1)
        // The chime is written under the generation read after that flush, and nothing flushes it.
        val cue = bodyOf(session(), "playCloseCue")
        assertFalse(cue.contains("flush("))
        assertTrue(cue.contains("p.write(p.startable(pcm), p.generation())"))
        assertTrue(release.indexOf("player?.release()") > chime)
    }

    @Test
    fun `the acknowledgement plays at end of turn from memory, gated by the words heard so far, and is cut when he speaks`() {
        val s = session()
        val schedule = bodyOf(s, "scheduleBackchannel")
        assertTrue(schedule.contains("?: ackWordsSkip(u)"))
        assertTrue(schedule.contains("fillerJobs.startBackchannel { playBackchannel(seq, eotAtMs) }"))
        val words = bodyOf(s, "ackWordsSkip")
        assertTrue(words.contains("heardSoFar?.takeIf { it.first == u.id }?.second"))
        // The final when the voice has it at end of turn, and the stop rule's own goodbye test.
        assertTrue(words.contains("u.finalText() ?: heardSoFar?.takeIf { it.first == u.id }?.second"))
        assertTrue(words.contains("goodbye = ::endsConversation"))
        assertTrue(words.contains("CrisisDetector::detect"))
        // The running transcript is kept with the speculation setting off too.
        val wire = bodyOf(s, "wireEarlySignals")
        assertTrue(wire.indexOf("heardSoFar = id to text") in 0 until wire.indexOf("if (!speculate) return"))
        // A cancelled acknowledgement is cut at once: his speech (onUserTurnStart) and a stop call cutAck.
        val clip = bodyOf(s, "playAckClip")
        assertTrue(clip.contains("finally {"))
        // The cut is made at once by whoever ends it (cutAck), never later from the cancelled clip.
        assertFalse(clip.contains("p.flush()"))
        assertTrue(clip.contains("ackCut.playing = true") && clip.contains("ackCut.playing = false"))
        // Never held past its own length and a margin: the answer waits on the ack's lock.
        assertTrue(clip.contains("p.awaitDrained(pcm.size * MS_PER_S / (p.sampleRate * PCM_BYTES_PER_SAMPLE) + ACK_DRAIN_MARGIN_MS)"))
        // The cut's order is AckCut's (behaviour-tested in AckCutTest); the session wires it.
        assertTrue(s.contains("AckCut(endJobs = { fillerJobs.endTurn() }, flush = { player?.flush() })"))
        assertTrue(s.contains("private fun cutAck() = ackCut.cut()"))
        assertTrue(s.substring(s.indexOf("private fun ackSteps(")).take(1_500).contains("clipPlayback.during { playAckClip(p, pcm) }"))
        val turnStart = s.substring(s.indexOf("private fun onUserTurnStart()")).take(800)
        assertTrue(turnStart.contains("cutAck()"))
    }

    @Test
    fun `the chime is logged either way and the route stays up until it has played`() {
        val s = session()
        val cue = bodyOf(s, "playCloseCue")
        assertTrue(cue.contains("CloseCue.skippedLine(reason"))
        assertTrue(cue.contains("CloseCue.playedLine(reason"))
        assertTrue(cue.contains("CloseCue.minPlayMs(chimeMs)"))
        val release = bodyOf(s, "releaseNow")
        val chime = release.indexOf("playCloseCue(reason)")
        listOf("player?.release()", "earpiece.stop()", "it.exit(\"cascade \$reason\")", "abandonFocus()").forEach {
            assertTrue(it, release.indexOf(it) > chime)
        }
    }

    @Test
    fun `every turn logs its first-sound line, also when the acknowledgement was skipped`() {
        val s = session()
        // Each sound marks the turn and logs the line at once.
        var at = s.indexOf("turnGap?.started(")
        var n = 0
        while (at >= 0) {
            val next = s.substring(at, minOf(s.length, at + 200))
            assertTrue("a sound without its line: ${next.take(60)}", next.contains("logFirstSound()"))
            n++
            at = s.indexOf("turnGap?.started(", at + 1)
        }
        assertEquals("ack, task line and wait line", 3, n)
        val audio = bodyOf(s, "answerAudioIn")
        assertTrue(audio.indexOf("turnGap?.answerHeard(now)") in 0 until audio.indexOf("logFirstSound()"))
        // A turn with no sound still gets one (kind=none): at the next turn, back to listening, and the close.
        val silent = "turnGap?.silentLine()?.let { LiveLatency.log(LiveLatency.TAG, it) }"
        val schedule = bodyOf(s, "scheduleBackchannel")
        assertTrue(schedule.indexOf(silent) in 0 until schedule.indexOf("turnGap = TurnGap(eotAtMs, lastLocalSpeechAtMs)"))
        assertTrue(bodyOf(s, "backToListening").contains(silent))
        assertTrue(bodyOf(s, "releaseNow").contains(silent))
        // Every skip names itself in that line.
        assertTrue(schedule.contains("turnGap?.noteSkip(skip.token)"))
        assertTrue(bodyOf(s, "backchannelStillDue").contains("turnGap?.noteSkip(reason)"))
        assertTrue(s.contains("turnGap?.noteSkip(\"no_clip_fits\")"))
        // The session's own skips are these, named: a new one fails here until it is listed.
        val due = bodyOf(s, "backchannelStillDue")
        val reasons = due.split("skip(\"").drop(1).map { it.substringBefore("\"") }.toSet()
        assertEquals(setOf("over_cue", "answer_first", "noise"), reasons)
    }

    @Test
    fun `every short clip is written so it starts at once, and the chime's consumption is measured`() {
        val s = session()
        // No short clip goes to a track raw: each is made startable ([ShortClip]).
        for (raw in listOf("p.write(ListenCue.earconPcm(), gen)", "p.write(cue.pcm, gen)", "chime.write(earcon, ")) {
            assertFalse(raw, s.contains(raw))
        }
        assertEquals(6, s.split("p.write(p.startable(").size - 1)
        assertTrue(s.contains("chime.write(chime.startable(earcon), chime.generation())"))
        val cue = bodyOf(s, "playCloseCue")
        assertTrue(cue.indexOf("val head0 = p.headFrames() ?: 0L") in 0 until cue.indexOf("CloseCue.playHeard("))
        assertTrue(cue.contains("p.write(p.startable(pcm), p.generation())"))
        assertTrue(cue.contains("played.copy(consumed = ((p.headFrames() ?: head0) - head0).coerceAtLeast(0), of = chimeFrames)"))
        // The track starts at one frame where it can.
        val player = File(sourceRoot(), "service/cascade/ReplyPlayer.kt").readText()
        assertTrue(player.contains("setStartThresholdInFrames("))
    }

    private fun session() = stripComments(MainSource.read(MainSource.CASCADE_SESSION))

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
