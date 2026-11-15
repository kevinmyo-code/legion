package com.kevin.legion.service.cascade

import org.json.JSONObject
import java.util.Locale

/**
 * One billed Gemini call's counts, for the cost audit (Kevin, 2026-11-15: "make sure we can audit
 * the costs of each convo too. the sts pipeline was too expensive. we need to know for this one
 * too"). **Counts and model ids only**: never text, never audio.
 *
 * Taken from the API's own `usageMetadata` when it came back ([present]); when it did not (a call
 * cut off, a failure), the measurable quantity is kept instead ([audioMs], [chars]) and the line says
 * `usage=absent`. [discarded] marks a call whose result nobody heard (cancelled, superseded, merged
 * into the next turn), so speculative work is costed too. `tools/voice_cost.py` prices the lines.
 */
internal data class CallUsage(
    val kind: String,
    val model: String,
    val present: Boolean,
    val prompt: Int = 0,
    val promptText: Int = 0,
    val promptAudio: Int = 0,
    val cached: Int = 0,
    val out: Int = 0,
    val outText: Int = 0,
    val outAudio: Int = 0,
    val thoughts: Int = 0,
    val audioMs: Long = 0,
    val chars: Int = 0,
    val discarded: Boolean = false,
    /** The call failed (HTTP error or network): no result came back, and it is not billed. */
    val failed: Boolean = false,
) {
    /** The `LIVE_LATENCY` line, parsed by `tools/voice_cost.py`. */
    fun line(): String = "gemini_usage kind=$kind model=$model usage=${if (present) "present" else "absent"} " +
        "prompt=$prompt prompt_text=$promptText prompt_audio=$promptAudio cached=$cached " +
        "out=$out out_text=$outText out_audio=$outAudio thoughts=$thoughts " +
        "audio_s=${seconds(audioMs)} chars=$chars discarded=$discarded" + if (failed) " failed=true" else ""

    companion object {
        const val LLM = "llm"
        const val GREETING = "greeting"
        const val STT = "stt"
        const val TTS = "tts"
        const val CACHE_CREATE = "cache_create"

        /**
         * From a `usageMetadata` object (null: none came back). Per-modality counts come from
         * `promptTokensDetails` / `candidatesTokensDetails`; a call with no breakdown counts its
         * prompt as text and its output as [defaultOut] (`TEXT`, or `AUDIO` for TTS).
         */
        @Suppress("LongParameterList")
        fun of(
            kind: String,
            model: String,
            usage: JSONObject?,
            audioMs: Long = 0,
            chars: Int = 0,
            discarded: Boolean = false,
            defaultOut: String = "TEXT",
        ): CallUsage {
            if (usage == null) {
                return CallUsage(kind, model, present = false, audioMs = audioMs, chars = chars, discarded = discarded)
            }
            val prompt = usage.optInt("promptTokenCount")
            val out = usage.optInt("candidatesTokenCount")
            val promptAudio = modality(usage, "promptTokensDetails", "AUDIO")
            val promptText = modality(usage, "promptTokensDetails", "TEXT")
                .takeIf { usage.has("promptTokensDetails") } ?: (prompt - promptAudio)
            val outAudio = modality(usage, "candidatesTokensDetails", "AUDIO")
                .takeIf { usage.has("candidatesTokensDetails") } ?: if (defaultOut == "AUDIO") out else 0
            return CallUsage(
                kind = kind,
                model = model,
                present = true,
                prompt = prompt,
                promptText = promptText,
                promptAudio = promptAudio,
                cached = usage.optInt("cachedContentTokenCount"),
                out = out,
                outText = (out - outAudio).coerceAtLeast(0),
                outAudio = outAudio,
                thoughts = usage.optInt("thoughtsTokenCount"),
                audioMs = audioMs,
                chars = chars,
                discarded = discarded,
            )
        }

        private fun modality(usage: JSONObject, field: String, name: String): Int {
            val arr = usage.optJSONArray(field) ?: return 0
            var n = 0
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                if (d.optString("modality") == name) n += d.optInt("tokenCount")
            }
            return n
        }

        fun seconds(ms: Long): String = String.format(Locale.US, "%.2f", ms / MS_PER_S)

        private const val MS_PER_S = 1_000.0
    }
}

/**
 * One conversation's calls, summed for the session-end line (`cascade_usage_summary`): per kind and
 * model, plus the context cache this session created (its tokens and TTL, so storage can be priced).
 * Thread-safe: calls report from IO threads.
 */
internal class UsageLedger {
    private data class Sum(
        var calls: Int = 0,
        var prompt: Long = 0,
        var promptText: Long = 0,
        var promptAudio: Long = 0,
        var cached: Long = 0,
        var out: Long = 0,
        var outText: Long = 0,
        var outAudio: Long = 0,
        var thoughts: Long = 0,
        var audioMs: Long = 0,
        var chars: Long = 0,
        var absent: Int = 0,
    )

    private val sums = LinkedHashMap<String, Sum>()
    private var cacheTokens = 0
    private var cacheTtlS = 0L
    private var cacheModel = ""
    private var specStarted = 0
    private var specKept = 0
    private var specDiscarded = 0

    /** Speculative drafts ([TurnDraft]): started, kept as the turn's answer, thrown away. */
    @Synchronized
    fun spec(started: Int = 0, kept: Int = 0, discarded: Int = 0) {
        specStarted += started
        specKept += kept
        specDiscarded += discarded
    }

    @Synchronized
    fun add(u: CallUsage) {
        // A discarded call is its own bucket, so the speculative spend is visible on its own.
        val key = (if (u.discarded) "discarded:${u.kind}" else u.kind) + "/" + u.model
        val s = sums.getOrPut(key) { Sum() }
        s.calls++
        s.prompt += u.prompt
        s.promptText += u.promptText
        s.promptAudio += u.promptAudio
        s.cached += u.cached
        s.out += u.out
        s.outText += u.outText
        s.outAudio += u.outAudio
        s.thoughts += u.thoughts
        s.audioMs += u.audioMs
        s.chars += u.chars
        if (!u.present) s.absent++
    }

    @Synchronized
    fun cacheCreated(model: String, tokens: Int, ttlS: Long) {
        cacheModel = model
        cacheTokens += tokens
        cacheTtlS = ttlS
    }

    /** One line, `|`-separated buckets, counts only. */
    @Synchronized
    fun summary(engine: String, turns: Int): String {
        val buckets = sums.entries.joinToString(" | ") { (key, s) ->
            "$key calls=${s.calls} prompt=${s.prompt} prompt_text=${s.promptText} prompt_audio=${s.promptAudio} " +
                "cached=${s.cached} out=${s.out} out_text=${s.outText} out_audio=${s.outAudio} " +
                "thoughts=${s.thoughts} audio_s=${CallUsage.seconds(s.audioMs)} chars=${s.chars} absent=${s.absent}"
        }
        val cache = if (cacheTokens > 0) {
            " cache_create=$cacheModel cache_tokens=$cacheTokens cache_ttl_s=$cacheTtlS"
        } else {
            ""
        }
        val spec = if (specStarted > 0) {
            " spec_started=$specStarted spec_kept=$specKept spec_discarded=$specDiscarded"
        } else {
            ""
        }
        val tail = if (buckets.isEmpty()) "" else " | $buckets"
        return "cascade_usage_summary engine=$engine turns=$turns$cache$spec$tail"
    }
}
