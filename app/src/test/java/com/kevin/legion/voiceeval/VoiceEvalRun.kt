@file:Suppress("MagicNumber", "MaxLineLength") // a by-hand run: poll intervals, caps and one-line logs

package com.kevin.legion.voiceeval

import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.service.LiveLatency
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.cascade.CascadePrefix
import com.kevin.legion.service.cascade.CascadePrompt
import com.kevin.legion.service.cascade.CascadeRules
import com.kevin.legion.service.cascade.CascadeSession
import com.kevin.legion.service.cascade.ContextCacheStore
import com.kevin.legion.service.cascade.OkHttpGemini
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * NOT part of the suite: the laptop voice eval against real Gemini. Skipped unless BOTH
 * `LEGION_GEMINI_KEY_FILE` (a file holding the key; never printed) and `LEGION_VOICE_EVAL` (comma
 * list of case files, e.g. `tools/voice_eval/cases.json`) are set. Run and score it with
 * `python tools/voice_eval.py laptop` (tools/voice_eval/README.md).
 *
 * Optional: `LEGION_VOICE_EVAL_MODEL` (default the cascade's own model),
 * `LEGION_VOICE_EVAL_OUT` (log file; default `app/build/voice-eval/eval-<model>.log`),
 * `LEGION_VOICE_EVAL_ONLY` (comma list of case ids), `LEGION_VOICE_EVAL_NO_CACHE=1` (inline only),
 * `LEGION_VOICE_EVAL_VOICE_CLAUSE_FILE` (a file whose text replaces [CascadePrompt.VOICE_CLAUSE], to run
 * the same cases against an older wording: the before half of a prompt change).
 *
 * Seeds [VoiceEvalFixture] (made-up data) into a fresh Room database and runs every case through
 * [VoiceEvalHarness]. Refuses to start if an engine is configured in this JVM, so a tool the model
 * picks can never write to a real household. The ask_* routers' own sub-agent calls are not
 * metered here (they run on [GeminiKeyProvider], outside the loop's meter): the cost is the text
 * loop's, plus the cache.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceEvalRun {
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
    fun `voice eval against real Gemini`() = runBlocking<Unit> {
        val keyFile = env("LEGION_GEMINI_KEY_FILE")
        val caseFiles = env("LEGION_VOICE_EVAL")
        assumeTrue("LEGION_GEMINI_KEY_FILE and LEGION_VOICE_EVAL not both set: skipped", keyFile != null && caseFiles != null)
        check(!EngineConfig(context).isConfigured()) { "an engine is configured in this JVM: refusing to run tools" }
        val key = File(keyFile!!).readText().trim()
        check(key.isNotBlank()) { "the key file is empty" }
        val model = env("LEGION_VOICE_EVAL_MODEL") ?: CascadeSession.MODEL
        val only = env("LEGION_VOICE_EVAL_ONLY")?.split(',')?.map { it.trim() }?.toSet()
        val outFile = File(env("LEGION_VOICE_EVAL_OUT") ?: "build/voice-eval/eval-$model.log")
        outFile.parentFile?.mkdirs()
        val lines = mutableListOf<String>()
        val out: (String, String) -> Unit = { tag, msg ->
            val line = "${LocalDateTime.now().format(STAMP)}  4242  4242 I $tag: $msg"
            synchronized(lines) { lines.add(line) }
        }

        // The ask_* routers' sub-agents read the process key: this JVM only, cleared in @After.
        CompanionProfile.saveGeminiKey(context, key)
        GeminiKeyProvider.init(context)
        VoiceEvalFixture.seed(context)

        val cases = caseFiles!!.split(',').filter { it.isNotBlank() }
            .flatMap { VoiceEvalCases.parse(VoiceEvalCases.resolve(it.trim()).readText()) }
            .filter { only == null || it.id in only }
        check(cases.isNotEmpty()) { "no cases to run" }

        val brain = AriaBrain.get(context)
        val split = CascadePrompt.split(brain.buildBaseInstruction())
        val prefix = CascadePrefix(model, withVoiceClause(split.cacheable), CascadeRules.declarations(LiveToolbox.declarations()))
        val preamble = CascadePrompt.preamble(split.volatile, brain.buildLiveContext())
        val transport = OkHttpGemini(key = { key })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val cache = if (env("LEGION_VOICE_EVAL_NO_CACHE") == "1") {
                null
            } else {
                ContextCacheStore(transport, scope, log = { out(LiveLatency.TAG, it) })
            }
            // As a conversation open does: create the cache first, so case 1 is priced like the rest.
            if (cache != null) {
                cache.warm(prefix)
                var tries = 0
                while (cache.state(prefix).startsWith("creating") && tries++ < 60) delay(500)
            }
            out(VoiceEvalHarness.TEST_TAG, "run_begin model=$model cases=${cases.size} cache=${cache?.state(prefix) ?: "off"}")
            // What the prompt was built from, so a laptop run can be compared with a phone's `pack=`.
            out(
                VoiceEvalHarness.TEST_TAG,
                "prompt pack=${brain.basePackToken} instruction_chars=${prefix.systemInstruction.length} " +
                    "voice_clause=${if (prefix.systemInstruction == split.cacheable) "current" else "replaced"} declarations=${prefix.functionDeclarations.length()}",
            )
            preamble.lines().filter { it.isNotBlank() }.forEach { out(VoiceEvalHarness.TEST_TAG, "preamble ${it.take(300)}") }
            val harness = VoiceEvalHarness(context, transport, cache, prefix, preamble, out)
            for (c in cases) {
                harness.runCase(c)
                println("VOICE_EVAL case ${c.id} done")
            }
            out(VoiceEvalHarness.TEST_TAG, "run_end")
        } finally {
            scope.cancel()
            outFile.writeText(synchronized(lines) { lines.joinToString("\n", postfix = "\n") })
            println("VOICE_EVAL wrote ${outFile.absolutePath} (${lines.size} lines)")
        }
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    /** The instruction with an older voice clause swapped in (`LEGION_VOICE_EVAL_VOICE_CLAUSE_FILE`), else as built. */
    private fun withVoiceClause(cacheable: String): String {
        val clause = env("LEGION_VOICE_EVAL_VOICE_CLAUSE_FILE")?.let { File(it).readText().trim() } ?: return cacheable
        val swapped = cacheable.replace(CascadePrompt.VOICE_CLAUSE, clause)
        check(swapped != cacheable) { "the voice clause was not found to replace" }
        return swapped
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }
}
