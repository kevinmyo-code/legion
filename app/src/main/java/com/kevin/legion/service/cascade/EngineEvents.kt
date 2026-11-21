package com.kevin.legion.service.cascade

import org.json.JSONArray
import org.json.JSONObject

/**
 * One call the engine asks this phone to run (`tool_request`, relay contract section 3; or one of
 * `done.device_actions`, [afterReply] true). Fields as the engine sends them (`relay.request`).
 */
internal data class EngineCall(
    val turnId: String,
    val callId: String,
    val name: String,
    val args: JSONObject,
    val writes: Boolean,
    val readThrough: Boolean,
    val actuates: Boolean,
    /** The turn's mark before this call's own: a write is refused on the phone too. */
    val tainted: Boolean,
    val deadlineMs: Long,
    val needs: List<String>,
    val confirm: String,
    val afterReply: Boolean = false,
)

/**
 * What the engine's turn stream says (`agents/voice/pipeline.py` event list), reduced to what the
 * phone acts on. `audio` and `filler` audio are not decoded: the phone speaks the sentences with its
 * own voice ([EngineBrain] says why), so they are [Other].
 */
internal sealed interface EngineEvent {
    data object Waiting : EngineEvent
    data class Turn(val turnId: String, val conversationId: String?, val seq: Int, val streamNo: Int) : EngineEvent
    data class Heard(val text: String) : EngineEvent
    data class Crisis(val text: String) : EngineEvent
    data class Working(val tool: String) : EngineEvent
    data class Tool(val name: String, val ok: Boolean, val outcome: String, val guard: String?) : EngineEvent
    data class Sentence(val text: String, val lineId: String, val role: String) : EngineEvent
    data class Request(val call: EngineCall) : EngineEvent

    /** The stream's last event. [calls] is set when [status] is [AWAITING_LIMB]. */
    data class Done(
        val status: String,
        val tainted: Boolean,
        val end: Boolean,
        val calls: List<String>,
        val ignored: String?,
        val deviceActions: List<EngineCall>,
    ) : EngineEvent

    /** `error {words}`: the turn could not run. */
    data class Failed(val words: String) : EngineEvent
    data class Other(val type: String) : EngineEvent

    companion object {
        const val AWAITING_LIMB = "awaiting_limb"
        const val NOTHING_HEARD = "nothing_heard"
    }
}

/**
 * Server-Sent Events, one line at a time: `event: <type>`, one `data: <json>`, a blank line ends
 * the event. Comment lines (`:`) and unknown fields are skipped. Not thread-safe: one per stream.
 */
internal class EngineSseReader {
    private var type: String? = null
    private val data = StringBuilder()

    /** The event a blank [line] completes, else null. */
    fun line(line: String): EngineEvent? {
        val event = when {
            line.isEmpty() -> complete()
            line.startsWith("event:") -> {
                type = line.removePrefix("event:").trim()
                null
            }
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
                null
            }
            else -> null
        }
        return event
    }

    private fun complete(): EngineEvent? {
        val t = type
        val d = data.toString()
        type = null
        data.setLength(0)
        return t?.let { EngineEventParse.parse(it, d) }
    }
}

internal object EngineEventParse {
    /** Event types whose data is never read (large or not acted on): not parsed at all. */
    private val SKIPPED = setOf("audio", "filler", "speech_failed", "speech_stopped")

    fun parse(type: String, data: String): EngineEvent {
        if (type in SKIPPED) return EngineEvent.Other(type)
        val json = runCatching { JSONObject(data.ifBlank { "{}" }) }.getOrNull()
        return json?.let { typed(type, it) } ?: EngineEvent.Other(type)
    }

    private fun typed(type: String, j: JSONObject): EngineEvent? = when (type) {
        "waiting" -> EngineEvent.Waiting
        "turn" -> EngineEvent.Turn(
            j.optString("turn_id"),
            j.optString("conversation_id").ifBlank { null },
            j.optInt("seq"),
            j.optInt("stream_no", 1),
        )
        "heard" -> EngineEvent.Heard(j.optString("text"))
        "crisis" -> EngineEvent.Crisis(j.optString("text"))
        "working" -> EngineEvent.Working(j.optString("tool"))
        "tool" -> EngineEvent.Tool(
            j.optString("name"),
            j.optBoolean("ok"),
            j.optString("outcome"),
            j.optString("guard").takeUnless { j.isNull("guard") || it.isBlank() },
        )
        "sentence" -> EngineEvent.Sentence(j.optString("text"), j.optString("line_id"), j.optString("role", "answer"))
        // A request with no call id or name cannot be answered or deduped: never run.
        "tool_request" -> call(j, afterReply = false).takeIf { it.callId.isNotBlank() && it.name.isNotBlank() }
            ?.let { EngineEvent.Request(it) }
        "done" -> done(j)
        "error" -> EngineEvent.Failed(j.optString("words"))
        else -> null
    }

    private fun done(j: JSONObject): EngineEvent.Done {
        val actions = j.optJSONArray("device_actions") ?: JSONArray()
        return EngineEvent.Done(
            status = j.optString("status", "done"),
            tainted = j.optBoolean("tainted"),
            end = j.optBoolean("end"),
            calls = strings(j.optJSONArray("calls")),
            ignored = j.optString("ignored").ifBlank { null },
            deviceActions = (0 until actions.length()).mapNotNull { i ->
                actions.optJSONObject(i)?.let { call(it, afterReply = true) }?.takeIf { it.callId.isNotBlank() }
            },
        )
    }

    /** One call from `tool_request` data (or a `device_actions` entry, or the status route's `calls`). */
    fun call(j: JSONObject, afterReply: Boolean): EngineCall = EngineCall(
        turnId = j.optString("turn_id"),
        callId = j.optString("call_id"),
        name = j.optString("name"),
        args = j.optJSONObject("args") ?: JSONObject(),
        writes = j.optBoolean("writes"),
        readThrough = j.optBoolean("read_through"),
        actuates = j.optBoolean("actuates"),
        tainted = j.optBoolean("tainted"),
        deadlineMs = j.optLong("deadline_ms", DEFAULT_DEADLINE_MS),
        needs = strings(j.optJSONArray("needs")),
        confirm = j.optString("confirm", "engine"),
        afterReply = afterReply || j.optBoolean("after_reply"),
    )

    fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }

    /** The engine's `limbs.DEFAULT_DEADLINE_MS`. */
    const val DEFAULT_DEADLINE_MS = 20_000L
}
