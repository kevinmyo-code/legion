@file:Suppress("MaxLineLength") // test fixtures and assertions read best on one line

package com.kevin.legion.voiceeval

import android.content.Context
import com.kevin.legion.service.ChatTurn
import com.kevin.legion.service.CoachRouting
import com.kevin.legion.service.LiveAudit
import com.kevin.legion.service.LiveLatency
import com.kevin.legion.service.LiveSessionController
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.ReadThroughWriteGuard
import com.kevin.legion.service.TextToolExecutor
import com.kevin.legion.service.cascade.CallUsage
import com.kevin.legion.service.cascade.CascadeLines
import com.kevin.legion.service.cascade.CascadePrefix
import com.kevin.legion.service.cascade.CascadePrompt
import com.kevin.legion.service.cascade.CascadeRules
import com.kevin.legion.service.cascade.CascadeSession
import com.kevin.legion.service.cascade.ContextCacheStore
import com.kevin.legion.service.cascade.GeminiStreamTransport
import com.kevin.legion.service.cascade.GeminiTextLoop
import com.kevin.legion.service.cascade.GeminiStt
import com.kevin.legion.service.cascade.GeminiTts
import com.kevin.legion.service.cascade.ScriptedLines
import com.kevin.legion.service.cascade.SttGuard
import com.kevin.legion.service.cascade.SttResult
import com.kevin.legion.service.cascade.CloudSpeechWire
import com.kevin.legion.service.cascade.SentenceSplitter
import com.kevin.legion.service.cascade.SpokenLanguages
import com.kevin.legion.service.cascade.ToolFillers
import com.kevin.legion.service.cascade.TtsTags
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.vehicle.AdapterBinding
import com.kevin.legion.vehicle.DongleCarController
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.VinResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * One eval case as the harness needs it: an id and what is said, turn by turn. [dongle] "unknown"
 * plugs a made-up OBD adapter in whose car nobody has named (no VIN); [sttAudio] speaks each turn
 * with the cloud voice and transcribes it with the cloud transcriber before the model hears it.
 */
internal data class EvalCase(
    val id: String,
    val turns: List<String>,
    val write: Boolean,
    val dongle: String? = null,
    val sttAudio: Boolean = false,
    /** The languages besides English he has in Settings for this case ([SpokenLanguages]). */
    val languages: List<String> = emptyList(),
)

/** Reads the shared case files (`tools/voice_eval/cases.json` and any private ones). */
internal object VoiceEvalCases {
    fun parse(json: String): List<EvalCase> {
        val arr: JSONArray = JSONObject(json).getJSONArray("cases")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val turns = c.optJSONArray("turns")?.let { t ->
                (0 until t.length()).map { t.getJSONObject(it).getString("say") }
            } ?: listOf(c.getString("say"))
            EvalCase(
                c.getString("id"),
                turns,
                c.optBoolean("write", false),
                dongle = c.optJSONObject("fixture")?.optString("dongle")?.takeIf { it.isNotBlank() },
                languages = c.optJSONObject("fixture")?.optJSONArray("languages")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }
                }.orEmpty(),
                sttAudio = c.optBoolean("stt_audio", false),
            )
        }
    }

    /** A path as given, or relative to the repo root (Gradle runs unit tests from `app/`). */
    fun resolve(path: String): File {
        val f = File(path)
        return if (f.isAbsolute || f.exists()) f else File("..", path)
    }
}

/**
 * Runs eval cases through the REAL cascade middle: [GeminiTextLoop] with the production prompt
 * prefix, the shared contents builder ([CascadePrompt.contents]) and the real tool code
 * ([LiveSurface.resolve], [LiveToolbox.dispatch], [LiveSurface.withRule]), the same order
 * `LiveSessionController.runTool` runs them. Writes the phone's own log lines ([LiveAudit] and
 * `gemini_usage` / `llm_usage` in `LIVE_LATENCY`), framed by `LIVE_TEST case_begin/case_end`, so
 * `tools/voice_eval.py` scores the laptop run and a phone run with one parser.
 *
 * What is NOT the phone's path: no speech (text in, text out), no speculative drafts, and the
 * screen-only tools (agenda sheet, saved places, generated views) answer success without a screen.
 */
internal class VoiceEvalHarness(
    private val context: Context,
    private val transport: GeminiStreamTransport,
    private val cache: ContextCacheStore?,
    private val prefix: CascadePrefix,
    private val preamble: String,
    private val out: (tag: String, msg: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val toolSeq = AtomicInteger(0)

    suspend fun runCase(case: EvalCase) {
        out(TEST_TAG, "case_begin id=${LiveAudit.token(case.id)}")
        if (case.dongle == "unknown") plugUnknownDongle()
        try {
            runTurns(case)
        } finally {
            if (case.dongle != null) unplugDongle()
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // the session's turn order, in one place
    private suspend fun runTurns(case: EvalCase) {
        val audit = LiveAudit(sink = { out(LiveAudit.TAG, it) }, onSession = {})
        audit.enabled = true
        audit.sessionStart("eval+${prefix.model}", emptySet())
        val loop = GeminiTextLoop(
            transport = transport,
            cache = cache,
            isMutating = LiveToolbox::isMutatingCall,
            isReadThrough = CascadeRules::isReadThrough,
            toolTimeoutMs = { CascadeSession.TOOL_BACKSTOP_MS },
            meter = { model, usage -> out(LiveLatency.TAG, CallUsage.of(CallUsage.LLM, model, usage).line()) },
            discarded = { model, usage, chars ->
                out(LiveLatency.TAG, CallUsage.of(CallUsage.LLM, model, usage, chars = chars, discarded = true).line())
            },
        )
        val history = mutableListOf<ChatTurn>()
        val languages = listOf(SpokenLanguages.ENGLISH) + case.languages.mapNotNull { SpokenLanguages.byName(it) }
        var current = SpokenLanguages.ENGLISH
        val started = now()
        for (said in case.turns) {
            val heardRaw = if (case.sttAudio) {
                transcribeSpoken(said, SpokenLanguages.sttInstruction(languages, current))
            } else {
                said
            }
            // The session's gate on what the transcriber wrote ([SttGuard]): a transcript it does not
            // believe never reaches the model; the missed line is said instead.
            val verdict = SttGuard.verdict(heardRaw, 0L, accepted = languages)
            if (verdict is SttGuard.Verdict.Drop) {
                audit.user(heardRaw, final = true)
                out(LiveLatency.TAG, "stt_rejected reason=${verdict.reason} chars=${heardRaw.length}")
                audit.assistant(ScriptedLines.MISSED, readThrough = false)
                out(TEST_TAG, "heard text=${LiveAudit.quote(ScriptedLines.MISSED)}")
                audit.turnComplete()
                history.add(ChatTurn(fromUser = true, text = heardRaw))
                history.add(ChatTurn(fromUser = false, text = ScriptedLines.MISSED))
                continue
            }
            val heard = (verdict as SttGuard.Verdict.Keep).text
            // The session's language rule (CascadeSession.followLanguage), cloud voice.
            val asked = SpokenLanguages.request(heard, languages)
            if (asked is SpokenLanguages.Switch.NotInSettings) {
                audit.user(heard, final = true)
                val line = SpokenLanguages.notInSettingsLine(asked.name)
                audit.assistant(line, readThrough = false)
                out(TEST_TAG, "heard text=${LiveAudit.quote(line)}")
                audit.turnComplete()
                continue
            }
            ((asked as? SpokenLanguages.Switch.To)?.language ?: SpokenLanguages.writtenIn(heard, languages))?.let {
                if (it != current) out(TEST_TAG, "language_switch to=${it.name}")
                current = it
            }
            audit.user(heard, final = true)
            val t0 = now()
            var firstMs = -1L
            var fillerSaid = false
            val raw = StringBuilder()
            val result = loop.answer(
                prefix,
                CascadePrompt.contents(history, heard, preamble + SpokenLanguages.turnNote(current)),
                executor(audit, heard),
                object : GeminiTextLoop.Listener {
                    override fun onText(delta: String) {
                        if (firstMs < 0) firstMs = now() - t0
                        raw.append(delta)
                    }

                    override fun onReadThrough(source: String) = audit.readThrough(source)

                    // The holding line the phone would say as these tools start ([ToolFillers]).
                    override fun onToolsStarting(calls: List<Pair<String, JSONObject>>) {
                        if (firstMs >= 0 || fillerSaid) return
                        val plan = ToolFillers.plan(
                            calls.map { (n, a) -> ToolFillers.Call(n, a, CascadeRules.isWrite(n, a)) },
                        ) ?: return
                        fillerSaid = true
                        val line = ToolFillers.pick(plan.lines, null, 0)
                        out(TEST_TAG, "filler at=${if (plan.slow) "start" else "late"} text=${LiveAudit.quote(line)}")
                    }
                },
            )
            out(LiveLatency.TAG, CascadeLines.usage(prefix.model, result.usage))
            val said = if (result.failure != null && result.text.isBlank()) {
                result.failureWords ?: result.failure.lead
            } else {
                result.text
            }
            audit.assistant(said, readThrough = result.readThrough)
            // What would be heard, even when the record redacts it (made-up data only): the scorer
            // reads this line first (2026-11-17, ADR 0090 guard cases score the spoken answer).
            out(TEST_TAG, "heard text=${LiveAudit.quote(said)}")
            // What the cloud voice would be handed, tags and all ([TtsTags], as ReplySpeaker applies them).
            val budget = TtsTags.Budget()
            val splitter = SentenceSplitter()
            val toVoice = (splitter.feed(raw.toString()) + listOfNotNull(splitter.flush()))
                .joinToString(" ") { TtsTags.forSpeech(it, budget) }
            out(TEST_TAG, "tts text=${LiveAudit.quote(toVoice)}")
            audit.turnComplete()
            out(
                LiveLatency.TAG,
                "test_turn_ms=${now() - t0} first_text_ms=$firstMs failure=${result.failure?.name ?: "none"}",
            )
            history.add(ChatTurn(fromUser = true, text = heard))
            history.add(ChatTurn(fromUser = false, text = if (result.readThrough) LiveAudit.REDACTED else said))
        }
        audit.sessionEnd("eval")
        out(TEST_TAG, "case_end id=${LiveAudit.token(case.id)} ms=${now() - started}")
    }

    /** A made-up adapter plugged in with no VIN, in a household of two cars: its car is unknown. */
    private suspend fun plugUnknownDongle() {
        unplugDongle()
        ObdBluetoothManager.setConnectedAddressForTest(EVAL_DONGLE)
        DongleCarController.onConnected(context, EVAL_DONGLE) { VinResponse.Result.NoData }
        out(TEST_TAG, "fixture dongle=${DongleCarController.state.value?.javaClass?.simpleName}")
    }

    private fun unplugDongle() {
        DongleCarController.resetForTest()
        AdapterBinding.resetForTest()
        ObdBluetoothManager.setConnectedAddressForTest(null)
        context.getSharedPreferences("dongle_car", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /**
     * [text] spoken by the cloud voice (Gemini TTS, 24 kHz) and heard back by the cloud transcriber
     * (16 kHz), as a spoken turn is: proves the transcriber's English pinning on real audio.
     */
    private suspend fun transcribeSpoken(text: String, instruction: String): String {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val tts = GeminiTts(transport, scope, meter = { m, u -> out(LiveLatency.TAG, CallUsage.of(CallUsage.TTS, m, u).line()) })
            val clip = tts.speak(text)
            val pcm24 = java.io.ByteArrayOutputStream()
            withTimeoutOrNull(STT_AUDIO_TIMEOUT_MS) { for (f in clip.frames) pcm24.write(f) }
            val pcm16 = downsample24to16(pcm24.toByteArray())
            val stt = GeminiStt(transport, scope, meter = { m, u -> out(LiveLatency.TAG, CallUsage.of(CallUsage.STT, m, u).line()) },
                log = { out(LiveLatency.TAG, it) }, instruction = { instruction })
            val r = stt.transcribe(pcm16)
            val heard = (r as? SttResult.Text)?.text.orEmpty()
            out(TEST_TAG, "stt_audio audio_ms=${pcm16.size / 32} result=${if (r is SttResult.Text) "text" else r.toString()} " +
                "voice=${CloudSpeechWire.VOICE} sent=${LiveAudit.quote(text)} heard=${LiveAudit.quote(heard)}")
            return heard
        } finally {
            scope.cancel()
        }
    }

    private fun executor(audit: LiveAudit, heard: String) = TextToolExecutor { name, args, readThrough ->
        val id = "e${toolSeq.incrementAndGet()}"
        audit.toolCall(id, name, false, args.toString())
        val t = now()
        val response = runTool(name, args, readThrough, heard)
        audit.toolResult(
            LiveAudit.ToolResult(
                id = id,
                name = name,
                success = response.optBoolean("success", !response.has("error")),
                ms = now() - t,
                async = false,
                sent = true,
                responseJson = response.toString(),
            ),
        )
        response
    }

    /** `LiveSessionController.runTool`'s order, minus the screens (there is none here). */
    @Suppress("ReturnCount") // the session-owned tools answer first, as in CascadeSession's executor
    private suspend fun runTool(name: String, args: JSONObject, readThrough: Boolean, userText: String): JSONObject {
        if (name == "end_conversation") {
            return JSONObject().put("success", true)
                .put("instruction", LiveSessionController.END_CONVERSATION_INSTRUCTION)
        }
        if (name in CascadeRules.LIVE_ONLY_TOOLS) {
            return JSONObject().put("success", false).put("message", "That only works on the Live voice engine.")
        }
        val (resolved, resolvedArgs) = LiveSurface.resolve(name, args)
        // ADR 0090: the controller's one write guard, in the same place.
        ReadThroughWriteGuard.refusal(resolved, resolvedArgs, readThrough)?.let { return it }
        val response = when (resolved) {
            // No Gmail on the laptop: the made-up inbox ([VoiceEvalFixture.mailAnswer]).
            in VoiceEvalFixture.MAIL_TOOLS -> VoiceEvalFixture.mailAnswer()
            "show_agenda_modal" -> CoachRouting.agendaRefusal(userText)
                ?.let { JSONObject().put("success", false).put("message", it) }
                ?: screenOnly()
            in SCREEN_TOOLS -> screenOnly()
            else -> withTimeoutOrNull(CascadeSession.TOOL_BACKSTOP_MS) {
                LiveToolbox.dispatch(context, resolved, resolvedArgs, readThrough, userText)
                    ?: JSONObject().put("success", true)
            } ?: JSONObject().put("success", false).put("message", "That took too long and timed out.")
        }
        return LiveSurface.withRule(resolved, response)
    }

    private fun screenOnly() = JSONObject().put("success", true)

    companion object {
        const val TEST_TAG = "LIVE_TEST"
        const val EVAL_DONGLE = "EVAL:DONGLE:00:01"
        private const val STT_AUDIO_TIMEOUT_MS = 20_000L

        /** 16-bit mono, 24 kHz to 16 kHz: two samples out of every three, linear between them. */
        fun downsample24to16(pcm: ByteArray): ByteArray {
            val n = pcm.size / 2
            fun at(i: Int): Int = if (i >= n) 0 else ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()
            val outN = n * 2 / 3
            val out = ByteArray(outN * 2)
            for (j in 0 until outN) {
                val pos = j * 3 / 2.0
                val i = pos.toInt()
                val frac = pos - i
                val v = (at(i) * (1 - frac) + at(i + 1) * frac).toInt()
                out[2 * j] = (v and 0xff).toByte()
                out[2 * j + 1] = ((v shr 8) and 0xff).toByte()
            }
            return out
        }

        /**
         * Tools whose whole effect is a screen (LiveSessionController owns them). `navigate` opens the
         * map and asks the route service, neither of which exists on the laptop.
         */
        val SCREEN_TOOLS = setOf("show_saved_places", "import_receipt", "show_generated_view", "navigate")
    }
}
