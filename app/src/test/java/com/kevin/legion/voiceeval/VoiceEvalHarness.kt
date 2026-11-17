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
import com.kevin.legion.service.tools.LiveSurface
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** One eval case as the harness needs it: an id and what is said, turn by turn. */
internal data class EvalCase(val id: String, val turns: List<String>, val write: Boolean)

/** Reads the shared case files (`tools/voice_eval/cases.json` and any private ones). */
internal object VoiceEvalCases {
    fun parse(json: String): List<EvalCase> {
        val arr: JSONArray = JSONObject(json).getJSONArray("cases")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val turns = c.optJSONArray("turns")?.let { t ->
                (0 until t.length()).map { t.getJSONObject(it).getString("say") }
            } ?: listOf(c.getString("say"))
            EvalCase(c.getString("id"), turns, c.optBoolean("write", false))
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
        val started = now()
        for (heard in case.turns) {
            audit.user(heard, final = true)
            val t0 = now()
            var firstMs = -1L
            val result = loop.answer(
                prefix,
                CascadePrompt.contents(history, heard, preamble),
                executor(audit, heard),
                object : GeminiTextLoop.Listener {
                    override fun onText(delta: String) {
                        if (firstMs < 0) firstMs = now() - t0
                    }

                    override fun onReadThrough(source: String) = audit.readThrough(source)
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

        /**
         * Tools whose whole effect is a screen (LiveSessionController owns them). `navigate` opens the
         * map and asks the route service, neither of which exists on the laptop.
         */
        val SCREEN_TOOLS = setOf("show_saved_places", "import_receipt", "show_generated_view", "navigate")
    }
}
