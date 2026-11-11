package com.kevin.legion.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * One Live `usageMetadata` report, counts only (voice cost audit 2026-11-10, row F7a).
 *
 * Field names are the Live API's `UsageMetadata`: `promptTokenCount`, `responseTokenCount`,
 * `thoughtsTokenCount`, `toolUsePromptTokenCount`, `cachedContentTokenCount`, `totalTokenCount`,
 * and the per-modality lists `promptTokensDetails` / `responseTokensDetails`, each entry
 * `{modality, tokenCount}`. `candidatesTokenCount` is the REST spelling of the response count and
 * is read as a fallback, as [com.kevin.legion.service.GeminiLiveSession] always did. A count the
 * report leaves out stays null, never zero.
 */
internal data class LiveUsage(
    val prompt: Int? = null,
    val response: Int? = null,
    val thoughts: Int? = null,
    val toolUsePrompt: Int? = null,
    val cached: Int? = null,
    val total: Int? = null,
    /** Modality (TEXT, AUDIO, IMAGE, VIDEO) to prompt tokens, in report order. */
    val promptByModality: Map<String, Int> = emptyMap(),
    /** Modality to response tokens, in report order. */
    val responseByModality: Map<String, Int> = emptyMap(),
) {
    /**
     * The LIVE_AUDIT `usage` line's fields: counts and modality names only, never content. Absent
     * counts are left out rather than written as 0. Example:
     * `prompt=24310 prompt_text=24100 prompt_audio=210 reply=95 reply_audio=95 thoughts=40 total=24445`.
     * `reply`, not `response`: the engine's LIVE_AUDIT parser reads a `response=` field to the end of
     * the line (`server/devicelogs/voice_audit.py` _REST).
     */
    fun auditFields(): String = buildList {
        prompt?.let { add("prompt=$it") }
        promptByModality.forEach { (m, n) -> add("prompt_${m.lowercase()}=$n") }
        cached?.let { add("cached=$it") }
        toolUsePrompt?.let { add("tool_use_prompt=$it") }
        response?.let { add("reply=$it") }
        responseByModality.forEach { (m, n) -> add("reply_${m.lowercase()}=$n") }
        thoughts?.let { add("thoughts=$it") }
        total?.let { add("total=$it") }
    }.joinToString(" ").ifEmpty { "empty=true" }

    /** The larger of each count, for folding repeat reports inside one generation. */
    fun maxWith(other: LiveUsage): LiveUsage = LiveUsage(
        prompt = maxOrNull(prompt, other.prompt),
        response = maxOrNull(response, other.response),
        thoughts = maxOrNull(thoughts, other.thoughts),
        toolUsePrompt = maxOrNull(toolUsePrompt, other.toolUsePrompt),
        cached = maxOrNull(cached, other.cached),
        total = maxOrNull(total, other.total),
        promptByModality = mergeMax(promptByModality, other.promptByModality),
        responseByModality = mergeMax(responseByModality, other.responseByModality),
    )

    companion object {
        fun parse(usage: JSONObject): LiveUsage = LiveUsage(
            prompt = usage.intOrNull("promptTokenCount"),
            response = usage.intOrNull("responseTokenCount") ?: usage.intOrNull("candidatesTokenCount"),
            thoughts = usage.intOrNull("thoughtsTokenCount"),
            toolUsePrompt = usage.intOrNull("toolUsePromptTokenCount"),
            cached = usage.intOrNull("cachedContentTokenCount"),
            total = usage.intOrNull("totalTokenCount"),
            promptByModality = modalities(usage.optJSONArray("promptTokensDetails")),
            responseByModality = modalities(
                usage.optJSONArray("responseTokensDetails") ?: usage.optJSONArray("candidatesTokensDetails"),
            ),
        )

        private fun JSONObject.intOrNull(key: String): Int? = if (has(key)) optInt(key) else null

        private fun modalities(details: JSONArray?): Map<String, Int> {
            if (details == null) return emptyMap()
            val out = LinkedHashMap<String, Int>()
            for (i in 0 until details.length()) {
                val d = details.optJSONObject(i) ?: continue
                val modality = d.optString("modality").ifBlank { "unspecified" }
                out[modality] = (out[modality] ?: 0) + d.optInt("tokenCount")
            }
            return out
        }

        private fun maxOrNull(a: Int?, b: Int?): Int? = when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }

        private fun mergeMax(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> {
            val out = LinkedHashMap(a)
            b.forEach { (k, v) -> out[k] = maxOf(out[k] ?: 0, v) }
            return out
        }
    }
}

/**
 * Folds Live usage reports into one figure per model GENERATION (row F7b).
 *
 * The old fold kept the MAX per socket, which equals the single largest turn when the API reports
 * per turn (the audit's reading of the Live API reference and forum: one `usageMetadata` per
 * generation, at its end). A socket's spend is then the SUM of its generations. In case a
 * generation ever reports more than once (cumulative within that generation), reports between two
 * boundaries are max-folded here first, so a repeat inside one generation is never added twice.
 * A boundary is a `toolCall` (the generation ended by asking for a tool), a `turnComplete`, or the
 * socket closing.
 */
internal class LiveUsageFold {
    private var pending: LiveUsage? = null

    /**
     * Folds [usage] into the open generation. A report whose prompt count differs from the open
     * one's starts a NEW generation (a prompt does not grow inside one generation; the next one
     * carries the longer history), and the finished one is returned for metering. That covers a
     * report that arrives in its own message after its turnComplete already passed.
     */
    @Synchronized
    fun report(usage: LiveUsage): LiveUsage? {
        val open = pending
        if (open != null && open.prompt != null && usage.prompt != null && open.prompt != usage.prompt) {
            pending = usage
            return open
        }
        pending = open?.maxWith(usage) ?: usage
        return null
    }

    /** The finished generation's usage, or null when nothing was reported since the last boundary. */
    @Synchronized
    fun boundary(): LiveUsage? = pending.also { pending = null }
}
