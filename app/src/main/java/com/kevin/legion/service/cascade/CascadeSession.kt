package com.kevin.legion.service.cascade

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.kevin.legion.BuildConfig
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CrisisDetector
import com.kevin.legion.ai.GeminiUsageMeter
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.auditContent
import com.kevin.legion.data.local.record
import com.kevin.legion.service.ChatTurn
import com.kevin.legion.service.FollowUpWindow
import com.kevin.legion.service.GeminiLiveSession
import com.kevin.legion.service.LiveAudit
import com.kevin.legion.service.LiveLatency
import com.kevin.legion.service.LiveSessionController
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.MicArbiter
import com.kevin.legion.service.MicLevel
import com.kevin.legion.service.MicSignal
import com.kevin.legion.service.Phase
import com.kevin.legion.service.TelephonyController
import com.kevin.legion.service.TextChatEngine
import com.kevin.legion.service.TextToolExecutor
import com.kevin.legion.service.WakePhrases
import com.kevin.legion.service.conversationlog.ConversationLog
import com.kevin.legion.service.turn.BargeInGate
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.EndOfTurnPolicy
import com.kevin.legion.service.turn.MicReopen
import com.kevin.legion.service.turn.TurnModels
import com.kevin.legion.service.turn.TurnTaker
import com.kevin.legion.service.turn.VoiceTurnSettings
import com.kevin.legion.vehicle.ActiveVehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One conversation on the laptop pipeline (Kevin, 2026-11-10: "full, wake word to tts the
 * alternative"): the phone records and decides turns; speech-to-text and text-to-speech run on the
 * laptop's voice service; Gemini text with tools runs on the phone in between.
 *
 * Built BESIDE [GeminiLiveSession], not on it: the mic loop and playback are trimmed copies of
 * Live's, so nothing on the proven path changed for this. What it shares is code that was already
 * shared by design: [TurnTaker] (Silero, Smart Turn, [BargeInGate]), [FollowUpWindow],
 * [CallAudioMode], [MicArbiter], [LiveAudit], [LiveLatency], and the controller's tool runner.
 *
 * Lifecycle: [open] (probe, connect, ack line, listen) -> turns -> [close] or a hand-over to Live.
 * **Any failure ends in Live, never in silence**: at open (laptop off, busy, not configured, turn
 * models missing) the conversation simply opens on Live; mid-way (laptop dropped, STT or TTS failed,
 * Silero threw) this session releases everything first and Live is told what was said and done
 * ([Handover]) so Kevin never repeats himself and no write runs twice.
 *
 * Threading: session state changes run on [loop] (one thread at a time). The mic runs on IO and
 * talks to [TurnTaker]; its sink posts back to [loop].
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class CascadeSession(
    context: Context,
    private val host: CascadeHost,
    private val fromWakeWord: Boolean,
    private val oneShot: Boolean,
) {
    private val ctx = context.applicationContext
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loop = Dispatchers.Default.limitedParallelism(1)

    private val audit = LiveAudit()
    private val ackLines = ACK

    private var conn: LaptopVoiceConnection? = null
    private var textLoop: GeminiTextLoop? = null
    private var prefix: CascadePrefix? = null
    private var preamble = ""
    private var plan: CascadeRules.TurnPlan? = null
    @Volatile private var turnTaker: TurnTaker? = null
    private var player: ReplyPlayer? = null
    private var callAudio: CallAudioMode? = null
    private var micJob: Job? = null
    private var micClaimed = false

    private val closing = AtomicBoolean(false)
    @Volatile var isOpen = true
        private set

    /** True while a reply is audible (or its tail is still settling): what [TurnTaker.feed] calls `playing`. */
    @Volatile private var playbackActive = false
    @Volatile private var currentUtt: SttUtterance? = null
    @Volatile private var lastLocalSpeechAtMs = 0L

    private var turnJob: Job? = null
    private var speaker: ReplySpeaker? = null
    private var speaking = false
    private val history = mutableListOf<ChatTurn>()
    private var carry = ""
    private var turn: TurnRecord? = null
    private var dismissAfterReply = false
    private var answeredOnce = false
    private val turnSeq = AtomicLong(0)
    private val toolSeq = AtomicInteger(0)
    private val toolsInFlight = AtomicInteger(0)

    private var followUp = FollowUpWindow.State()
    private var followUpJob: Job? = null
    private var followUpArmedAtMs = 0L

    /** What one turn has done so far: what [Handover] needs if the laptop drops mid-turn. */
    private class TurnRecord(val heard: String) {
        val reply = StringBuffer()
        val toolsRan = mutableListOf<String>()
        val writesDone = mutableListOf<String>()
        @Volatile var readThrough = false
    }

    // --- open ------------------------------------------------------------------------------------

    fun open() {
        host.phase(Phase.CONNECTING, "Connecting...")
        scope.launch(loop) {
            try {
                openNow()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "open failed", e)
                handOver(Handover.Moment.AtOpen, "open", "exception_${e.javaClass.simpleName}")
            }
        }
    }

    @Suppress("ReturnCount", "LongMethod")
    private suspend fun openNow() {
        val started = System.currentTimeMillis()
        val config = VoiceEngineSettings.laptop(ctx)
        // A laptop that just failed to answer is not asked again for a little while: the wake word
        // must not wait out the probe on every conversation while Tailscale is off.
        if (config.isComplete && started - lastUnreachableAtMs < RECENT_UNREACHABLE_MS) {
            handOver(Handover.Moment.AtOpen, "open", "unreachable_recent")
            return
        }
        val models = withContext(Dispatchers.IO) { TurnModels.awaitReady(ctx, MODEL_WAIT_MS) }
        val probe = if (config.isComplete) LaptopProbe.probe(config, PROBE_TIMEOUT_MS) else null
        lastUnreachableAtMs = if (probe is LaptopProbe.Result.Unreachable) System.currentTimeMillis() else 0L
        val probeMs = when (probe) {
            is LaptopProbe.Result.Ready -> probe.ms
            is LaptopProbe.Result.Busy -> probe.ms
            is LaptopProbe.Result.Unreachable -> probe.ms
            else -> -1L
        }
        CascadeRules.openRefusal(config.isComplete, models != null, probe)?.let {
            LiveLatency.log(LiveLatency.TAG, "cascade_probe_ms=$probeMs probe=${probe?.token ?: "none"}")
            handOver(Handover.Moment.AtOpen, "open", it)
            return
        }
        models!!
        val c = LaptopVoiceConnection(
            connector = OkHttpWire(),
            config = config,
            clientName = "legion-phone/${BuildConfig.VERSION_CODE}",
            scope = scope,
            onDrop = { reason -> scope.launch(loop) { onLaptopDropped(reason) } },
        )
        conn = c
        when (val r = c.open(OPEN_TIMEOUT_MS)) {
            is LaptopVoiceConnection.OpenResult.Failed -> {
                lastUnreachableAtMs = System.currentTimeMillis()
                handOver(Handover.Moment.AtOpen, "open", "connect_${r.reason}")
                return
            }
            LaptopVoiceConnection.OpenResult.Ready -> VoiceEngineSettings.noteStatus(ctx, VoiceEngineSettings.statusWords("ok"))
        }
        if (closing.get()) return
        if (!MicArbiter.request(MicArbiter.Claimant.LIVE_TURN) { scope.launch(loop) { onMicPreempted() } }) {
            host.notice("Didn't start - the microphone is in use")
            shutdown("mic_held")
            host.closed("mic_held")
            return
        }
        micClaimed = true

        // The prompt and the cache start now; the ack line plays while they build.
        val brain = AriaBrain.get(ctx)
        val prefixJob = scope.async(Dispatchers.IO) {
            val split = CascadePrompt.split(brain.buildBaseInstruction())
            val live = runCatching { brain.buildLiveContext() }.getOrDefault("")
            preamble = CascadePrompt.preamble(split.volatile, live)
            CascadePrefix(MODEL, split.cacheable, CascadeRules.declarations(LiveToolbox.declarations()))
        }
        val cache = CACHE ?: ContextCacheStore(OkHttpGemini(), CACHE_SCOPE, log = { LiveLatency.log(LiveLatency.TAG, it) })
            .also { CACHE = it }
        textLoop = GeminiTextLoop(
            transport = OkHttpGemini(),
            cache = cache,
            isMutating = LiveToolbox::isMutatingCall,
            isReadThrough = { GeminiLiveSession.isEpisodicExcludedTool(it) },
            toolTimeoutMs = { TOOL_BACKSTOP_MS },
            meter = { model, usage ->
                KeyHealth.noteOk()
                GeminiUsageMeter.recordRestCall(
                    model,
                    usage?.takeIf { it.has("promptTokenCount") }?.optInt("promptTokenCount"),
                    usage?.takeIf { it.has("candidatesTokenCount") }?.optInt("candidatesTokenCount"),
                    usage?.takeIf { it.has("totalTokenCount") }?.optInt("totalTokenCount"),
                )
            },
        )

        val smartReady = models.smartTurn != null && TurnModels.smartTurnBroken == null
        val p = CascadeRules.turnPlan(
            serviceEndsTurns = c.endsTurns,
            smartSetting = VoiceTurnSettings.smartEndOfTurn(ctx),
            smartTurnReady = smartReady,
            bargeSetting = VoiceTurnSettings.bargeIn(ctx),
            route = CallAudioMode.route(audioManager),
            inCall = TelephonyController.isInCall,
        )
        plan = p
        turnTaker = TurnTaker(
            p.config,
            models.silero.stream(),
            models.smartTurn.takeIf { p.config.smartTurn },
            sink,
            p.endBaseMs,
        )
        if (p.config.bargeIn) {
            val mode = CallAudioMode(audioManager)
            LiveLatency.log(TurnTaker.BARGE_TAG, "call audio ${mode.enter()} (laptop pipeline)")
            callAudio = mode
        }
        player = ReplyPlayer(c.sampleRate) { callAudio?.isActive == true }
        audit.enabled = ConversationLog.auditWantedAfterReading(ctx)
        audit.sessionStart("$ENGINE_TOKEN+$MODEL", emptySet(), brain.basePackToken)
        LiveLatency.log("LIVE_TURN", "config manual=true bargeIn=${p.config.bargeIn} smartTurn=${p.config.smartTurn} (${p.config.note})")
        LiveLatency.log(LiveLatency.TAG, CascadeLines.open(System.currentTimeMillis() - started, probeMs, p, c.streamsText))
        startMic()
        prefix = prefixJob.await()
        prefix?.let { cache.warm(it) }
        if (closing.get()) return
        speakScripted(ackLines.next())
    }

    // --- the door from the controller ------------------------------------------------------------

    /**
     * A tap (or a second wake word) while this conversation is open: interrupts a reply that is
     * playing (ADR 0081), otherwise ends the conversation, the same two things a tap does on Live.
     * Returns true when it interrupted.
     */
    fun onTap(): Boolean {
        if (speaking) {
            scope.launch(loop) { interrupt("tap") }
            return true
        }
        close("stopped", userEnded = true)
        return false
    }

    /** The side key while open: end after the next answer. */
    fun makeOneShot() {
        scope.launch(loop) { dismissAfterReply = true }
    }

    /** Ends the conversation. [userEnded] shows "Ended the chat". Safe to call more than once. */
    fun close(reason: String, userEnded: Boolean = false) {
        scope.launch(loop) {
            if (closing.get()) return@launch
            shutdown(reason)
            if (userEnded) host.notice("Ended the chat - tap to start another")
            host.closed(reason)
        }
    }

    /** The controller is going away or Live is taking the mic: release everything now. */
    fun destroy() {
        close("destroyed")
    }

    // --- turns -----------------------------------------------------------------------------------

    private val sink = object : TurnTaker.Sink {
        override fun sendActivityStart() {
            val c = conn ?: return
            var self: SttUtterance? = null
            val u = c.begin { prob ->
                val utt = self ?: return@begin
                scope.launch(loop) { onServiceEndpoint(utt, prob) }
            }
            self = u
            currentUtt = u
            scope.launch(loop) { onUserTurnStart() }
        }

        override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {
            currentUtt?.audio(pcm, offset, length)
        }

        override fun sendActivityEnd() {
            val u = currentUtt ?: return
            currentUtt = null
            u.end()
            val at = System.currentTimeMillis()
            val source = plan?.phoneEnd ?: CascadeRules.EotSource.PHONE_DETECTOR
            scope.launch(loop) { onTurnEnd(u, at, source, null) }
        }

        override fun onUserTurnStart(bargeIn: Boolean) {
            LiveLatency.log("LIVE_FOLLOWUP", "local speech start bargeIn=$bargeIn")
        }

        override fun onEndOfTurn(end: EndOfTurnPolicy.Action.EndActivity) {
            LiveLatency.log(LiveLatency.TAG, "eot_ms=${end.silenceMs} eot_reason=${end.reason.wire} st_checks=${end.scores.size}")
        }

        override fun onBargeIn(fire: BargeInGate.Event.Fire) {
            scope.launch(loop) { interrupt("barge_in") }
        }

        override fun onBargeInDisabled(selfInterrupts: Int) {
            host.notice("Interrupting turned off for this conversation")
            scope.launch(loop) {
                callAudio?.let { if (it.isActive) it.exit("self-interrupts") }
                player?.rebuild()
            }
        }

        override fun onLocalSpeech() {
            lastLocalSpeechAtMs = System.currentTimeMillis()
        }

        override fun onSileroFailed(reason: String) {
            TurnModels.markBroken("Silero threw: $reason")
            scope.launch(loop) { handOver(currentMoment(), "turn", "silero_failed") }
        }

        override fun onSmartTurnFailed(reason: String) {
            TurnModels.smartTurnBroken = reason
        }

        override fun log(tag: String, message: String) = LiveLatency.log(tag, message)
    }

    private fun onUserTurnStart() {
        followUpEvent(FollowUpWindow.Event.UserSpeech)
        audit.activityStart()
        if (!speaking) host.phase(Phase.LISTENING, "Listening...")
        // He started again before the last answer began playing: that answer is dropped and his
        // words carry into this turn, so a mid-thought pause never splits one request in two.
        val pending = turn
        if (pending != null && !speaking && turnJob?.isActive == true && pending.toolsRan.isEmpty()) {
            carry = (carry + " " + pending.heard).trim()
            turnJob?.cancel()
            speaker?.interrupt()
            LiveLatency.log("LIVE_TURN", "turn_merged reason=speech_resumed")
        }
    }

    /** The laptop's own end of turn (Kyutai endpointing): end the utterance the phone is streaming. */
    private fun onServiceEndpoint(u: SttUtterance, prob: Float) {
        if (currentUtt !== u) return
        currentUtt = null
        u.end()
        // The phone's own turn is over too: forget it, so its silence cap does not end a second one.
        turnTaker?.reset()
        onTurnEnd(u, System.currentTimeMillis(), CascadeRules.EotSource.SERVICE, prob)
    }

    private fun onTurnEnd(u: SttUtterance, eotAtMs: Long, source: CascadeRules.EotSource, prob: Float?) {
        val silence = lastLocalSpeechAtMs.takeIf { it > 0 }?.let { eotAtMs - it }
        LiveLatency.log(LiveLatency.TAG, CascadeLines.eot(source, silence, prob))
        audit.activityEnd()
        host.phase(Phase.THINKING, "...")
        val previous = turnJob
        turnJob = scope.launch(loop) {
            previous?.join()
            runTurn(u, eotAtMs)
        }
    }

    @Suppress("LongMethod", "ReturnCount")
    private suspend fun runTurn(u: SttUtterance, eotAtMs: Long) {
        val stt = u.result(STT_FINAL_TIMEOUT_MS)
        LiveLatency.log(LiveLatency.TAG, "stt_final_ms=${System.currentTimeMillis() - eotAtMs}" +
            ((stt as? SttResult.Text)?.decodeMs?.let { " stt_decode_ms=$it" } ?: ""))
        val words = when (stt) {
            is SttResult.Failed -> {
                if (stt.reason == "cancelled") return
                handOver(if (carry.isBlank()) Handover.Moment.LostWords else heardMoment(carry), "stt", stt.reason)
                return
            }
            is SttResult.Text -> stt.text
        }
        val heard = (carry + " " + words).trim()
        if (heard.isBlank()) {
            backToListening()
            return
        }
        carry = ""
        audit.user(heard, final = true)
        if (CrisisDetector.detect(heard)) {
            shutdown("crisis")
            host.crisis()
            return
        }
        if (WakePhrases.isSleepPhrase(heard)) {
            recordTurn(heard, "")
            endByVoice()
            return
        }
        val record = TurnRecord(heard)
        turn = record
        val seq = turnSeq.getAndIncrement()
        val pfx = prefix ?: run {
            handOver(heardMoment(heard), "prompt", "prefix_missing")
            return
        }
        val reply = newSpeaker(eotAtMs)
        var outcome: ReplySpeaker.Outcome = ReplySpeaker.Outcome.Interrupted
        try {
            val result = textLoop!!.answer(
                pfx,
                contentsFor(heard),
                executorFor(record, seq),
                listenerFor(record, reply),
            )
            LiveLatency.log(LiveLatency.TAG, CascadeLines.usage(MODEL, result.usage))
            if (result.failure != null && !reply.anyText) {
                reply.say(result.failureWords ?: result.failure.lead)
            } else {
                reply.finishText()
            }
            outcome = reply.await()
            val said = reply.spoken().ifBlank { result.text }
            when (outcome) {
                is ReplySpeaker.Outcome.Failed -> {
                    handOver(heardMoment(heard, record), "tts", outcome.reason)
                    return
                }
                else -> Unit
            }
            recordTurn(heard, if (outcome == ReplySpeaker.Outcome.Interrupted) reply.spoken() else said, record.readThrough, seq)
            answeredOnce = true
        } catch (e: CancellationException) {
            // Interrupted (tap, barge-in) or merged into the next turn: keep only what was heard.
            withContext(NonCancellable) {
                reply.interrupt()
                if (carry.isBlank() || record.toolsRan.isNotEmpty()) recordTurn(heard, reply.spoken(), record.readThrough, seq)
            }
            throw e
        } finally {
            if (turn === record) turn = null
        }
        if (outcome == ReplySpeaker.Outcome.Played) afterReply()
    }

    private fun contentsFor(heard: String) = run {
        val capped = TextChatEngine.capHistory(history + ChatTurn(fromUser = true, text = heard))
        val contents = TextChatEngine.historyContents(capped)
        // The date, the place and this conversation's context ride in the first user turn (never
        // in the cached instruction).
        if (preamble.isNotEmpty() && contents.length() > 0) {
            val first = contents.getJSONObject(0)
            val part = first.getJSONArray("parts").getJSONObject(0)
            part.put("text", preamble + part.optString("text"))
        }
        contents
    }

    private fun executorFor(record: TurnRecord, seq: Long) = TextToolExecutor { name, args, readThrough ->
        val id = "c${toolSeq.incrementAndGet()}"
        audit.toolCall(id, name, false, args.toString())
        toolsInFlight.incrementAndGet()
        followUpEventAsync(FollowUpWindow.Event.ToolCall)
        if (!speaking) host.phase(Phase.THINKING, "Working...")
        val started = System.currentTimeMillis()
        val response = try {
            when (name) {
                // Arm, do not fire: the sign-off is spoken first (Live's ticket 11 rule).
                "end_conversation" -> {
                    scope.launch(loop) { dismissAfterReply = true }
                    JSONObject().put("success", true).put("instruction", LiveSessionController.END_CONVERSATION_INSTRUCTION)
                }
                in CascadeRules.LIVE_ONLY_TOOLS ->
                    JSONObject().put("success", false).put("message", "That only works on the Live voice engine.")
                else -> host.runTool(name, args, readThrough, record.heard, seq)
            }
        } finally {
            toolsInFlight.decrementAndGet()
        }
        audit.toolResult(
            LiveAudit.ToolResult(
                id = id,
                name = name,
                success = response.optBoolean("success", !response.has("error")),
                ms = System.currentTimeMillis() - started,
                async = false,
                sent = true,
                responseJson = response.toString(),
            ),
        )
        response
    }

    private fun listenerFor(record: TurnRecord, reply: ReplySpeaker) = object : GeminiTextLoop.Listener {
        override fun onText(delta: String) {
            record.reply.append(delta)
            reply.text(delta)
        }

        override fun onToolCalls(names: List<String>) {
            synchronized(record) { record.toolsRan.addAll(names) }
        }

        override fun onToolResult(name: String, args: JSONObject, response: JSONObject, ms: Long, writeDone: Boolean) {
            if (writeDone) synchronized(record) { record.writesDone.add(name) }
            LiveLatency.log(LiveLatency.TAG, "tool_ms=$ms tool=$name sent=true async=false")
            if (toolsInFlight.get() == 0) followUpEventAsync(FollowUpWindow.Event.ToolResponse)
        }

        override fun onReadThrough(source: String) {
            record.readThrough = true
            audit.readThrough(source)
        }

        override fun onFirstPart(round: Int, ms: Long) {
            LiveLatency.log(LiveLatency.TAG, "llm_first_ms=$ms round=$round")
        }
    }

    private fun newSpeaker(eotAtMs: Long?): ReplySpeaker {
        val c = conn!!
        val p = player!!
        val gen = p.generation()
        var ttsAskedAtMs = System.currentTimeMillis()
        return ReplySpeaker(
            tts = c,
            scope = scope,
            play = { pcm -> p.write(pcm, gen) },
            firstFrameTimeoutMs = TTS_FIRST_AUDIO_TIMEOUT_MS,
            onFirstAudio = {
                val now = System.currentTimeMillis()
                eotAtMs?.let { LiveLatency.log(LiveLatency.TAG, "eot_reply_ms=${now - it}") }
                LiveLatency.log(LiveLatency.TAG, "tts_first_ms=${now - ttsAskedAtMs}")
                scope.launch(loop) { onReplyAudible() }
            },
            onClipStarted = { text ->
                ttsAskedAtMs = System.currentTimeMillis()
                audit.assistant(text, readThrough = turn?.readThrough == true)
            },
        ).also { speaker = it }
    }

    private fun onReplyAudible() {
        if (closing.get()) return
        speaking = true
        playbackActive = true
        turnTaker?.let { if (it.bargeIn) it.onReplyStart() }
        followUpEvent(FollowUpWindow.Event.ModelSpeech)
        host.phase(Phase.SPEAKING, "Speaking...")
    }

    /** The reply played to the end: drain, settle, listen again (or hang up if he asked). */
    private suspend fun afterReply() {
        player?.awaitDrained()
        delay(MicReopen.settleMs(CallAudioMode.route(audioManager)))
        speaking = false
        playbackActive = false
        turnTaker?.onReplyEnd()
        audit.turnComplete()
        if (dismissAfterReply || (oneShot && answeredOnce)) {
            host.closingTone()
            shutdown("dismissed")
            host.closed("dismissed")
            return
        }
        backToListening()
    }

    private fun backToListening() {
        if (closing.get()) return
        host.phase(Phase.LISTENING, "Listening...")
        followUpEvent(FollowUpWindow.Event.TurnComplete)
        followUpEvent(FollowUpWindow.Event.PlaybackDrained)
    }

    /** Tap or barge-in: stop the reply now. A tool already running finishes and is reported. */
    private fun interrupt(source: String) {
        if (closing.get()) return
        val wasSpeaking = speaking
        speaker?.interrupt()
        player?.flush()
        speaking = false
        playbackActive = false
        turnTaker?.onReplyEnd()
        turnJob?.cancel()
        audit.interrupted(source)
        LiveLatency.log("LIVE_TURN", "interrupted source=$source speaking=$wasSpeaking")
        host.phase(Phase.LISTENING, "Listening...")
        if (source == "tap") backToListening()
    }

    /** A scripted line (the opening acknowledgement): no model call. */
    private fun speakScripted(line: String) {
        val reply = newSpeaker(null)
        reply.say(line)
        turnJob = scope.launch(loop) {
            when (val o = reply.await()) {
                ReplySpeaker.Outcome.Played -> afterReply()
                is ReplySpeaker.Outcome.Failed -> handOver(Handover.Moment.Idle, "tts", o.reason)
                ReplySpeaker.Outcome.Interrupted -> Unit
            }
        }
    }

    private suspend fun endByVoice() {
        host.closingTone()
        shutdown("sleep_phrase")
        host.notice("Conversation closed")
        host.closed("sleep_phrase")
    }

    // --- records ---------------------------------------------------------------------------------

    private fun recordTurn(heard: String, said: String, readThrough: Boolean = false, seq: Long = turnSeq.getAndIncrement()) {
        if (heard.isNotBlank()) history.add(ChatTurn(fromUser = true, text = heard))
        // A read-through reply (mail, web) is never kept as history or in the record in clear.
        val kept = if (readThrough) LiveAudit.REDACTED else said
        if (said.isNotBlank()) history.add(ChatTurn(fromUser = false, text = kept))
        host.turnTranscript(heard, said)
        val vehicle = ActiveVehicle.current(ctx)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dao = CarDatabase.getDatabase(ctx).conversationAuditDao()
                val uploaded = com.kevin.legion.backend.conversationAuditUploadedThroughId(ctx)
                if (heard.isNotBlank()) {
                    dao.record(turnSeq = seq, kind = ConversationAudit.Kind.USER, content = heard, vehicleId = vehicle, uploadedThroughId = uploaded)
                }
                if (said.isNotBlank()) {
                    dao.record(
                        turnSeq = seq,
                        kind = ConversationAudit.Kind.COMPANION,
                        content = auditContent(said, readThrough),
                        redacted = readThrough,
                        vehicleId = vehicle,
                        uploadedThroughId = uploaded,
                    )
                }
            }
        }
    }

    // --- follow-up window (Live's own fold, ticket 17: 8 s of quiet closes) -----------------------

    private fun followUpEventAsync(event: FollowUpWindow.Event) {
        scope.launch(loop) { followUpEvent(event) }
    }

    private fun followUpEvent(event: FollowUpWindow.Event) {
        val d = FollowUpWindow.step(followUp, event)
        followUp = d.state
        when (d.action) {
            FollowUpWindow.Action.ARM -> armFollowUp()
            FollowUpWindow.Action.CANCEL -> followUpJob?.cancel()
            FollowUpWindow.Action.CLOSE -> onFollowUpExpired()
            FollowUpWindow.Action.NONE -> Unit
        }
        d.reason?.let { LiveLatency.log("LIVE_FOLLOWUP", "reason=$it") }
    }

    private fun armFollowUp() {
        followUpJob?.cancel()
        followUpArmedAtMs = System.currentTimeMillis()
        LiveLatency.log("LIVE_FOLLOWUP", "armed ms=${FollowUpWindow.WINDOW_MS} engine=$ENGINE_TOKEN")
        followUpJob = scope.launch(loop) {
            delay(FollowUpWindow.WINDOW_MS)
            val now = System.currentTimeMillis()
            followUpEvent(
                FollowUpWindow.Event.Elapsed(
                    toolsInFlight = toolsInFlight.get(),
                    msSinceLocalSpeech = lastLocalSpeechAtMs.takeIf { it >= followUpArmedAtMs }?.let { now - it },
                ),
            )
        }
    }

    private fun onFollowUpExpired() {
        if (closing.get()) return
        scope.launch(loop) {
            LiveLatency.log("LIVE_FOLLOWUP", "expired engine=$ENGINE_TOKEN")
            host.closingTone()
            shutdown("follow_up")
            host.notice("Conversation closed")
            host.closed("follow_up")
        }
    }

    // --- failure ---------------------------------------------------------------------------------

    private fun onLaptopDropped(reason: String) {
        if (closing.get()) return
        scope.launch(loop) { handOver(currentMoment(), "socket", reason) }
    }

    private fun onMicPreempted() {
        if (closing.get()) return
        micClaimed = false
        scope.launch(loop) {
            LiveLatency.log("LIVE_TURN", "laptop pipeline preempted by a higher-priority mic claimant")
            shutdown("preempted")
            host.closed("preempted")
        }
    }

    private fun heardMoment(heard: String, r: TurnRecord? = null) = Handover.Moment.Heard(
        heard = heard,
        reply = r?.reply?.toString().orEmpty(),
        toolsRan = r?.let { synchronized(it) { it.toolsRan.toList() } }.orEmpty(),
        writesDone = r?.let { synchronized(it) { it.writesDone.toList() } }.orEmpty(),
        readThrough = r?.readThrough == true,
    )

    /** Where the conversation stands, for a hand-over that was not triggered inside a turn. */
    private fun currentMoment(): Handover.Moment {
        val r = turn
        return when {
            r != null -> heardMoment(r.heard, r)
            currentUtt != null -> Handover.Moment.LostWords
            else -> Handover.Moment.Idle
        }
    }

    /**
     * Releases everything, then opens Live for this conversation. Strictly in that order: a mic or
     * call-audio mode still held when Live starts would break his phone audio for the trip.
     */
    private suspend fun handOver(moment: Handover.Moment, stage: String, reason: String) {
        if (!closing.compareAndSet(false, true)) return
        LiveLatency.log(LiveLatency.TAG, CascadeLines.fallback(stage, reason))
        val words = VoiceEngineSettings.statusWords(if (stage == "open") reason else "${stage}_$reason")
        VoiceEngineSettings.noteStatus(ctx, words)
        host.notice(
            if (moment == Handover.Moment.AtOpen) "Laptop voice: $words" else "Laptop voice dropped - switched to Live",
        )
        release(reason)
        host.handOverToLive(fromWakeWord, Handover.opener(moment))
    }

    /** Idempotent full release. */
    private suspend fun shutdown(reason: String) {
        if (!closing.compareAndSet(false, true)) return
        release(reason)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun release(reason: String) = withContext(NonCancellable) {
        isOpen = false
        followUpJob?.cancel()
        speaker?.interrupt()
        turnJob?.takeIf { it != currentJob() }?.cancel()
        currentUtt?.cancel()
        currentUtt = null
        try {
            micJob?.cancelAndJoin()
        } catch (e: Exception) {
            Log.w(TAG, "mic join: ${e.message}")
        }
        turnTaker?.reset()
        player?.release()
        callAudio?.let { if (it.isActive) it.exit("laptop pipeline $reason") }
        if (micClaimed) MicArbiter.release(MicArbiter.Claimant.LIVE_TURN)
        micClaimed = false
        conn?.close(reason)
        if (conn != null) audit.sessionEnd(reason)
        LiveLatency.log("LIVE_TURN", "laptop pipeline closed reason=$reason")
        scope.launch {
            delay(SCOPE_GRACE_MS)
            scope.cancel()
        }
    }

    private suspend fun currentJob(): Job? = kotlin.coroutines.coroutineContext[Job]

    // --- microphone (a trimmed copy of Live's micLoop) -----------------------------------------------

    private fun startMic() {
        micJob = scope.launch(Dispatchers.IO) { micLoop() }
    }

    @SuppressLint("MissingPermission") // checked first, below
    @Suppress("LongMethod", "NestedBlockDepth")
    private suspend fun micLoop() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.notice("Didn't start - microphone permission is off")
            close("no_mic_permission")
            return
        }
        delay(MIC_HANDOFF_MS)
        val minBuf = AudioRecord.getMinBufferSize(INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(INPUT_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, INPUT_RATE))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setPrivacySensitive(true)
        val record = try {
            builder.build()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "AudioRecord build failed: ${e.message}")
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            scope.launch(loop) { handOver(currentMoment(), "mic", "audio_record_init") }
            return
        }
        val effects = attachEffects(record.audioSessionId)
        val buffer = ByteArray(CHUNK_BYTES)
        try {
            record.startRecording()
            Log.d(TAG, "AudioRecord opened (laptop pipeline) sessionId=${record.audioSessionId}")
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) {
                    Log.w(TAG, "record.read failed: $n")
                    delay(READ_ERROR_BACKOFF_MS)
                    continue
                }
                if (n == 0) continue
                MicLevel.publish(MicSignal.peakAmplitude(buffer, n))
                turnTaker?.feed(buffer, n, playbackActive)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "mic loop error: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            effects.forEach { runCatching { it.release() } }
            Log.d(TAG, "AudioRecord released (laptop pipeline)")
        }
    }

    private fun attachEffects(sessionId: Int): List<AudioEffect> {
        val out = mutableListOf<AudioEffect>()
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true; out.add(it) }
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.also { it.enabled = true; out.add(it) }
        }
        return out
    }

    companion object {
        private const val TAG = "CascadeSession"

        /** The text model (design doc: fastest measured, cheapest, no scheduled price change). */
        const val MODEL = "gemini-3.5-flash-lite"

        /** `session_start model=` carries the engine, so Live and laptop sessions part in the log. */
        const val ENGINE_TOKEN = "laptop"

        /**
         * The contract asks for 250 ms. Through a tunnel, a cold probe would miss that often enough
         * that nearly every conversation would land on Live and there would be nothing to compare.
         * The real figure is logged (`cascade_probe_ms`, `probe_ms`) so this can come down.
         */
        const val PROBE_TIMEOUT_MS = 1_200L
        const val OPEN_TIMEOUT_MS = 2_500L
        const val MODEL_WAIT_MS = 1_500L

        /** Above the service's own 5 s STT budget (contract section 6), so its `timeout` error wins. */
        const val STT_FINAL_TIMEOUT_MS = 6_000L

        /** Above the service's 3 s first-audio budget. */
        const val TTS_FIRST_AUDIO_TIMEOUT_MS = 4_000L

        /** The loop's own backstop; the controller's runner already applies each tool's real ceiling. */
        const val TOOL_BACKSTOP_MS = 47_000L

        private const val INPUT_RATE = 16_000
        private const val CHUNK_BYTES = 3_200
        private const val MIC_HANDOFF_MS = 150L
        private const val READ_ERROR_BACKOFF_MS = 50L
        private const val SCOPE_GRACE_MS = 2_000L

        private val ACK = AckLines()

        /** How long a failed probe or connect sends conversations straight to Live. */
        const val RECENT_UNREACHABLE_MS = 30_000L
        @Volatile private var lastUnreachableAtMs = 0L

        /** One cache store for the process: the cache outlives any one conversation. */
        @Volatile private var CACHE: ContextCacheStore? = null
        private val CACHE_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
