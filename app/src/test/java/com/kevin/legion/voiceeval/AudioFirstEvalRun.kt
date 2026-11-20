@file:Suppress("MagicNumber", "MaxLineLength", "LongMethod") // a by-hand run: one-line logs

package com.kevin.legion.voiceeval

import com.kevin.legion.ai.AgentProtocol
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.TextToolExecutor
import com.kevin.legion.service.WakePhrases
import com.kevin.legion.service.cascade.AudioFirstGate
import com.kevin.legion.service.cascade.AudioFirstRound
import com.kevin.legion.service.cascade.CascadePrefix
import com.kevin.legion.service.cascade.CascadePrompt
import com.kevin.legion.service.cascade.CascadeRequests
import com.kevin.legion.service.cascade.CascadeRules
import com.kevin.legion.service.cascade.CascadeSession
import com.kevin.legion.service.cascade.GeminiStreamTransport
import com.kevin.legion.service.cascade.GeminiStt
import com.kevin.legion.service.cascade.GeminiTextLoop
import com.kevin.legion.service.cascade.OkHttpGemini
import com.kevin.legion.service.cascade.StreamOutcome
import com.kevin.legion.service.cascade.StreamedTurn
import com.kevin.legion.service.cascade.SttGuard
import com.kevin.legion.service.cascade.SttResult
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Collections

/**
 * NOT part of the suite: the audio-first comparison (Kevin, 2026-11-19, speed plan item 4b). Skipped
 * unless BOTH `LEGION_GEMINI_KEY_FILE` (never printed) and `LEGION_AUDIO_FIRST_EVAL` (comma list of
 * case files) are set. `LEGION_AUDIO_FIRST_ONLY` limits the case ids, `LEGION_VOICE_EVAL_MODEL` the model.
 *
 * Each case's line is spoken by the cloud voice into a made-up 16 kHz turn ([VoiceEvalHarness.spokenClip])
 * and round 1 runs BOTH ways on the same audio and prompt (text: transcript then round 1; audio: round 1 on
 * the audio, [AudioFirstRound]). Scored: the tools AND, for every call, its arguments (item, amount, car,
 * list...) as the model sent them. No tool runs in the comparison.
 *
 * Then the gate cases, with the real [GeminiTextLoop] and a fake executor (no tool code runs):
 * - near silence: a clip with no speech is never sent (no model call);
 * - a forced transcript timeout on a write case and on a plain reply: the gate fails, so a write the model
 *   calls gets [AudioFirstGate.NOT_HEARD] and nothing runs; round 2 carries the note, not the audio;
 * - round 2 after a read: the words go in as text, the audio is not re-sent;
 * - a stop phrase: decided from the transcript ([WakePhrases.isSleepPhrase]).
 * Writes `build/voice-eval/audio-first-<model>.txt`. Times: round 1 first part (measured), the transcript
 * (measured); the voice's first audio is NOT measured here.
 */
@RunWith(RobolectricTestRunner::class)
class AudioFirstEvalRun {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drain() {
        CompanionProfile.saveGeminiKey(context, "")
        GeminiKeyProvider.init(context)
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `audio-first round 1 against the text path on the same made-up audio`() = runBlocking<Unit> {
        val keyFile = System.getenv("LEGION_GEMINI_KEY_FILE")
        val caseFiles = System.getenv("LEGION_AUDIO_FIRST_EVAL")
        assumeTrue("LEGION_GEMINI_KEY_FILE and LEGION_AUDIO_FIRST_EVAL not both set: skipped", keyFile != null && caseFiles != null)
        check(!EngineConfig(context).isConfigured()) { "an engine is configured in this JVM: refusing to run" }
        val key = File(keyFile!!).readText().trim()
        check(key.isNotBlank()) { "the key file is empty" }
        val model = System.getenv("LEGION_VOICE_EVAL_MODEL") ?: CascadeSession.MODEL
        val only = System.getenv("LEGION_AUDIO_FIRST_ONLY")?.split(',')?.map { it.trim() }?.toSet()
        CompanionProfile.saveGeminiKey(context, key)
        GeminiKeyProvider.init(context)
        VoiceEvalFixture.seed(context)
        val all = caseFiles!!.split(',').filter { it.isNotBlank() }
            .flatMap { VoiceEvalCases.parse(VoiceEvalCases.resolve(it.trim()).readText()) }
            .filter { it.turns.size == 1 }
        val cases = all.filter { only == null || it.id in only }
        check(cases.isNotEmpty()) { "no cases to run" }

        val brain = AriaBrain.get(context)
        val split = CascadePrompt.split(brain.buildBaseInstruction())
        val prefix = CascadePrefix(model, split.cacheable, CascadeRules.declarations(LiveToolbox.declarations()))
        val preamble = CascadePrompt.preamble(split.volatile, brain.buildLiveContext())
        val transport = OkHttpGemini(key = { key })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lines = Collections.synchronizedList(mutableListOf<String>())
        fun say(line: String) {
            lines += line
            println("AUDIO_FIRST $line")
        }
        val harness = VoiceEvalHarness(context, transport, null, prefix, preamble, out = { _, _ -> })
        val stt = GeminiStt(transport, scope)
        val rows = mutableListOf<Row>()
        try {
            for (c in cases) {
                val pcm = harness.spokenClip(c.turns.single())
                val t0 = System.currentTimeMillis()
                val heard = (stt.transcribe(pcm) as? SttResult.Text)?.text.orEmpty()
                val sttMs = System.currentTimeMillis() - t0
                val text = roundOne(transport, prefix, CascadePrompt.contents(emptyList(), heard, preamble))
                val audio = roundOne(transport, prefix, AudioFirstRound.contents(emptyList(), pcm, preamble))
                val row = Row(c.id, c.write, sttMs, text, audio)
                rows += row
                say(row.line())
            }
            gateCases(transport, prefix, preamble, harness, stt, all, ::say)
        } finally {
            scope.cancel()
        }
        val agree = rows.count { it.toolsAgree }
        val argsAgree = rows.count { it.argsAgree }
        val writes = rows.filter { it.write }
        val textFirst = rows.map { it.sttMs + it.text.firstMs }.sorted()
        val audioFirst = rows.map { it.audio.firstMs }.sorted()
        say(
            "summary model=$model cases=${rows.size} tools_agree=$agree/${rows.size} args_agree=$argsAgree/${rows.size} " +
                "write_args_agree=${writes.count { it.argsAgree }}/${writes.size} " +
                "median_text_first_part_ms=${median(textFirst)} (stt+round1) median_audio_first_part_ms=${median(audioFirst)} (round1) " +
                "median_stt_ms=${median(rows.map { it.sttMs }.sorted())} audio_failed=${rows.count { !it.audio.ok }} text_failed=${rows.count { !it.text.ok }}",
        )
        val out = File("build/voice-eval/audio-first-$model.txt")
        out.parentFile?.mkdirs()
        out.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** The gate's own cases: silence, a lost transcript on a write and on a reply, round 2, a stop. */
    @Suppress("LongParameterList")
    private suspend fun gateCases(
        transport: OkHttpGemini,
        prefix: CascadePrefix,
        preamble: String,
        harness: VoiceEvalHarness,
        stt: GeminiStt,
        all: List<EvalCase>,
        say: (String) -> Unit,
    ) {
        // Near silence: the local voiced measure says silent, so no request is made at all.
        val silence = ByteArray(16_000 * 2)
        val voiced = SttGuard.voicedMs(silence)
        say("gate case=near_silence voiced_ms=$voiced sent=${!AudioFirstGate.silent(voiced)}")

        val write = all.firstOrNull { it.id == "write_todo" } ?: all.first { it.write }
        val reply = all.firstOrNull { it.id == "time_now" } ?: all.first { !it.write }
        val read = all.firstOrNull { it.id == "cars" } ?: all.first { !it.write }
        for ((name, case, words) in listOf(
            Triple("timeout_write", write, null),
            Triple("timeout_reply", reply, null),
            Triple("round2_after_read", read, "words"),
        )) {
            val pcm = harness.spokenClip(case.turns.single())
            val heard = if (words == null) null else (stt.transcribe(pcm) as? SttResult.Text)?.text
            val gate = AudioFirstGate()
            if (heard == null) gate.fail() else gate.pass(heard)
            val ran = Collections.synchronizedList(mutableListOf<String>())
            val refused = Collections.synchronizedList(mutableListOf<String>())
            val wrote = Collections.synchronizedList(mutableListOf<String>())
            val executor = TextToolExecutor { n, a, _ ->
                val k = key(n, a)
                val isWrite = CascadeRules.isWrite(n, a)
                if (isWrite && gate.awaitForWrite() == null) {
                    refused += k
                    AudioFirstGate.NOT_HEARD
                } else {
                    ran += k
                    if (isWrite) wrote += k
                    JSONObject().put("success", true).put("note", "invented result for the eval")
                }
            }
            val recorder = Recording(transport)
            val loop = GeminiTextLoop(recorder, null, isMutating = CascadeRules::isWrite, isReadThrough = CascadeRules::isReadThrough)
            val text = StringBuilder()
            val r = loop.answer(
                prefix,
                AudioFirstRound.contents(emptyList(), pcm, preamble),
                executor,
                object : GeminiTextLoop.Listener { override fun onText(delta: String) { text.append(delta) } },
                userWords = { gate.awaitForRound() },
            )
            val resent = recorder.bodies.drop(1).count { it.toString().contains("inlineData") }
            say(
                "gate case=$name rounds=${recorder.bodies.size} audio_resent=$resent ran=$ran refused=$refused " +
                    "writes_run=${wrote.size} failure=${r.failure?.name ?: "none"} answer_words=${text.split(Regex("\\s+")).count { it.isNotBlank() }}",
            )
        }

        // A stop: decided from the transcript, before anything is said.
        val stop = harness.spokenClip("that's all, thanks")
        val heard = (stt.transcribe(stop) as? SttResult.Text)?.text.orEmpty()
        say("gate case=stop_phrase stop=${WakePhrases.isSleepPhrase(heard, "Alfred", afterQuestion = false)} heard_words=${heard.split(' ').size}")
    }

    /** A transport that keeps every request body (to see whether round 2 re-sent the audio). */
    private class Recording(private val inner: GeminiStreamTransport) : GeminiStreamTransport {
        val bodies = Collections.synchronizedList(mutableListOf<JSONObject>())
        override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
            bodies += body
            return inner.stream(model, body, onEvent)
        }
    }

    private class Round(val calls: List<String>, val args: List<String>, val firstMs: Long, val ok: Boolean)

    private class Row(val id: String, val write: Boolean, val sttMs: Long, val text: Round, val audio: Round) {
        val toolsAgree: Boolean get() = text.calls.toSet() == audio.calls.toSet()
        val argsAgree: Boolean get() = text.args.sorted() == audio.args.sorted()
        fun line() = "case=$id write=$write tools_agree=$toolsAgree args_agree=$argsAgree stt_ms=$sttMs " +
            "text_first_ms=${text.firstMs} audio_first_ms=${audio.firstMs} text_calls=${text.calls} audio_calls=${audio.calls}" +
            if (argsAgree) "" else " text_args=${text.args} audio_args=${audio.args}"
    }

    /** Round 1 only: its calls (with normalised arguments) and the time to its first part. */
    private suspend fun roundOne(transport: GeminiStreamTransport, prefix: CascadePrefix, contents: JSONArray): Round {
        val turn = StreamedTurn()
        val t0 = System.currentTimeMillis()
        var first = -1L
        val outcome = transport.stream(prefix.model, CascadeRequests.inline(prefix, contents, false)) { ev ->
            val before = turn.text.length + turn.calls.size
            turn.onEvent(ev) {}
            if (first < 0 && turn.text.length + turn.calls.size > before) first = System.currentTimeMillis() - t0
        }
        if (first < 0) first = System.currentTimeMillis() - t0
        return Round(turn.calls.map { key(it.name, it.args) }, turn.calls.map { argsOf(it) }, first, outcome == StreamOutcome.Ok)
    }

    private fun key(name: String, args: JSONObject): String {
        val r = LiveSurface.resolve(name, args)
        val action = r.args.optString("action").ifBlank { r.args.optString("domain") }
        return if (action.isBlank()) r.name else "${r.name}/$action"
    }

    /** The call's arguments, strings lowercased and trimmed of end punctuation, keys sorted. */
    private fun argsOf(call: AgentProtocol.FunctionCall): String {
        val r = LiveSurface.resolve(call.name, call.args)
        return key(call.name, call.args) + r.args.keys().asSequence().sorted().joinToString(",", "{", "}") { k ->
            val v = r.args.opt(k)
            "$k=" + if (v is String) v.lowercase().trim().trimEnd('.', '!', '?') else v.toString()
        }
    }

    private fun median(xs: List<Long>): Long = if (xs.isEmpty()) -1 else xs[xs.size / 2]
}
