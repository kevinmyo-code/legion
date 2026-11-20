@file:Suppress("MagicNumber", "MaxLineLength") // a by-hand run: one-line logs

package com.kevin.legion.voiceeval

import com.kevin.legion.ai.AgentProtocol
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.cascade.AudioFirstRound
import com.kevin.legion.service.cascade.CascadePrefix
import com.kevin.legion.service.cascade.CascadePrompt
import com.kevin.legion.service.cascade.CascadeRequests
import com.kevin.legion.service.cascade.CascadeRules
import com.kevin.legion.service.cascade.CascadeSession
import com.kevin.legion.service.cascade.GeminiStt
import com.kevin.legion.service.cascade.OkHttpGemini
import com.kevin.legion.service.cascade.StreamOutcome
import com.kevin.legion.service.cascade.StreamedTurn
import com.kevin.legion.service.cascade.SttResult
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * NOT part of the suite: the audio-first comparison (Kevin, 2026-11-19, speed plan item 4b). Skipped
 * unless BOTH `LEGION_GEMINI_KEY_FILE` (never printed) and `LEGION_AUDIO_FIRST_EVAL` (comma list of
 * case files, e.g. `tools/voice_eval/cases.json`) are set. `LEGION_AUDIO_FIRST_ONLY` limits the case
 * ids, `LEGION_VOICE_EVAL_MODEL` the model (default the cascade's).
 *
 * Each case's first line is spoken by the cloud voice into a made-up 16 kHz turn
 * ([VoiceEvalHarness.spokenClip]), then round 1 runs BOTH ways on the same audio and prompt:
 * - text: the cloud transcriber, then round 1 on the transcript (today's path);
 * - audio: round 1 on the audio itself ([AudioFirstRound]).
 * One request per way, no tool runs: the comparison is round 1's tool choice (names, with the action
 * a folded call resolves to) and its latency. Writes `build/voice-eval/audio-first-<model>.txt`.
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
        val cases = caseFiles!!.split(',').filter { it.isNotBlank() }
            .flatMap { VoiceEvalCases.parse(VoiceEvalCases.resolve(it.trim()).readText()) }
            .filter { (only == null || it.id in only) && it.turns.size == 1 && !it.write }
        check(cases.isNotEmpty()) { "no cases to run" }

        val brain = AriaBrain.get(context)
        val split = CascadePrompt.split(brain.buildBaseInstruction())
        val prefix = CascadePrefix(model, split.cacheable, CascadeRules.declarations(LiveToolbox.declarations()))
        val preamble = CascadePrompt.preamble(split.volatile, brain.buildLiveContext())
        val transport = OkHttpGemini(key = { key })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lines = mutableListOf<String>()
        val log: (String, String) -> Unit = { _, msg -> synchronized(lines) { lines += msg } }
        val harness = VoiceEvalHarness(context, transport, null, prefix, preamble, log)
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
                val row = Row(c.id, sttMs, text, audio)
                rows += row
                println("AUDIO_FIRST ${row.line()}")
            }
        } finally {
            scope.cancel()
        }
        val agree = rows.count { it.agree }
        val textMs = rows.map { it.sttMs + it.text.ms }.sorted()
        val audioMs = rows.map { it.audio.ms }.sorted()
        val summary = "AUDIO_FIRST summary model=$model cases=${rows.size} agree=$agree/${rows.size} " +
            "median_text_ms=${textMs[textMs.size / 2]} (stt+round1) median_audio_ms=${audioMs[audioMs.size / 2]} (round1) " +
            "audio_failed=${rows.count { !it.audio.ok }} text_failed=${rows.count { !it.text.ok }}"
        println(summary)
        val out = File("build/voice-eval/audio-first-$model.txt")
        out.parentFile?.mkdirs()
        out.writeText((rows.map { it.line() } + summary).joinToString("\n", postfix = "\n"))
    }

    private class Round(val calls: List<String>, val ms: Long, val ok: Boolean, val words: Int)

    private class Row(val id: String, val sttMs: Long, val text: Round, val audio: Round) {
        val agree: Boolean get() = text.calls.toSet() == audio.calls.toSet()
        fun line() = "case=$id agree=$agree stt_ms=$sttMs text_round_ms=${text.ms} audio_round_ms=${audio.ms} " +
            "text_calls=${text.calls} audio_calls=${audio.calls} text_words=${text.words} audio_words=${audio.words}"
    }

    /** Round 1 only: one request, its tool calls (as `tool/action` when folded) and how long it took. */
    private suspend fun roundOne(transport: OkHttpGemini, prefix: CascadePrefix, contents: JSONArray): Round {
        val turn = StreamedTurn()
        val text = StringBuilder()
        val t0 = System.currentTimeMillis()
        val outcome = transport.stream(prefix.model, CascadeRequests.inline(prefix, contents, false)) { ev ->
            turn.onEvent(ev) { text.append(it) }
        }
        val ms = System.currentTimeMillis() - t0
        return Round(turn.calls.map { key(it) }, ms, outcome == StreamOutcome.Ok, text.split(Regex("\\s+")).count { it.isNotBlank() })
    }

    private fun key(call: AgentProtocol.FunctionCall): String {
        val r = LiveSurface.resolve(call.name, call.args)
        val action = r.args.optString("action").ifBlank { r.args.optString("domain") }
        return if (action.isBlank()) r.name else "${r.name}/$action"
    }
}
