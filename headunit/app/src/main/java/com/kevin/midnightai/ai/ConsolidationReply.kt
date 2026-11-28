package com.kevin.midnightai.ai

import com.kevin.midnightai.data.local.CompanionMemory
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The model's answer to [MemoryConsolidator]'s distill question: the chat's summary (issue #452)
 * and its 0 to 5 durable memories. Pure, so the parse is tested without a model.
 *
 * Two shapes parse: the object `{"summary": ..., "memories": [...]}` asked for since issue #452,
 * and the bare memory array the question asked for before it (no summary then). Anything else is
 * null, and the caller leaves the session's turns for the next pass.
 */
object ConsolidationReply {
    data class Memory(val text: String, val importance: Int, val category: String)

    data class Parsed(val summary: String?, val memories: List<Memory>)

    private val VALID_CATEGORIES = setOf(
        CompanionMemory.Category.CAR_ANCHORED,
        CompanionMemory.Category.DRIVER,
        CompanionMemory.Category.RELATIONSHIP,
    )

    fun parse(raw: String): Parsed? {
        val objStart = raw.indexOf('{')
        val arrStart = raw.indexOf('[')
        return try {
            if (objStart != -1 && (arrStart == -1 || objStart < arrStart)) {
                val end = raw.lastIndexOf('}')
                if (end < objStart) return null
                val o = JSONObject(raw.substring(objStart, end + 1))
                val summary = if (o.isNull("summary")) null else o.optString("summary").trim().ifEmpty { null }
                Parsed(summary, memories(o.optJSONArray("memories") ?: JSONArray()))
            } else {
                val end = raw.lastIndexOf(']')
                if (arrStart == -1 || end < arrStart) return null
                Parsed(null, memories(JSONArray(raw.substring(arrStart, end + 1))))
            }
        } catch (e: JSONException) {
            null // the caller logs the failed pass and retries
        }
    }

    private fun memories(arr: JSONArray): List<Memory> = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val text = o.optString("text").trim()
        val category = o.optString("category").trim().lowercase()
        if (text.isBlank() || category !in VALID_CATEGORIES) return@mapNotNull null
        Memory(text, o.optInt("importance", DEFAULT_IMPORTANCE).coerceIn(1, MAX_IMPORTANCE), category)
    }

    private const val DEFAULT_IMPORTANCE = 5
    private const val MAX_IMPORTANCE = 10
}
