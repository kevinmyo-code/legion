package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.memory.MemoryController
import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.memory.MemoryWording
import org.json.JSONArray
import org.json.JSONObject

/**
 * Memory by voice, on the engine (memory-and-vault ticket 09, ADR 0075): ONE `memory` tool with an
 * `action` (recall, remember, forget, list), the trade `books`, `browser` and `app_update` made,
 * because [LiveSetupPayloadSizeTest] bills every declaration on every reconnect and the ceiling had
 * 37 tokens left. It replaces `remember` and `recall_memory` (1,045 chars of declarations) and is
 * smaller than the two were. Every action calls [MemoryController], the controller the Memory
 * screen calls (ADR 0035).
 *
 * **CLAUDE.md section 7 holds through the move.** `remember` after a read-through tool in the same
 * turn is refused before anything is sent ([LiveToolbox.rememberBlockedByReadThroughTool], fed the
 * boolean [LiveToolbox.dispatch] receives); nothing else here can carry mail into a memory.
 *
 * **Results carry each memory's id** so "forget that" has something to point at, and say when they
 * came from the phone's copy because the engine was out of reach (`source`, and the note, which the
 * LIVE_AUDIT `tool_result` line records). `forget` says deleted for good only on the engine's 204.
 */
object MemoryToolbox {

    const val TOOL_NAME = "memory"

    /** How many memories a `list` result carries; the Memory screen shows the rest. */
    private const val LIST_SPOKEN = 20

    /** Test seam: a controller over fakes. Production is [MemoryController.forContext]. */
    internal var controllerFactory: (Context) -> MemoryController = MemoryController::forContext

    private const val DESCRIPTION =
        "Your long-term memory of the user. action=recall (query: a few words, empty for the latest) when " +
            "they ask what you remember, mention someone or something from before, or a past detail would " +
            "make the reply personal; remember (text, in their words) only when they ask you to; forget (id " +
            "from a result) when they say forget that; list for all you keep about them. Say only what the " +
            "result says."

    fun declarations(): JSONArray = JSONArray().put(
        fn(
            name = "memory",
            description = DESCRIPTION,
            params = JSONObject()
                .put("action", prop("string", null, listOf("recall", "remember", "forget", "list")))
                .put("query", prop("string"))
                .put("text", prop("string"))
                .put("id", prop("string"))
                .put("shared", prop("boolean", "True only if they say the household may know it.")),
            required = listOf("action"),
        ),
    )

    // `name = "..."` written out above, not via TOOL_NAME: tools/voice_guide.py and VoiceGuideDataTest
    // find tool names by scanning for that exact shape.
    private fun fn(name: String, description: String, params: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", params).put("required", JSONArray(required)),
            )

    private fun prop(type: String, description: String? = null, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type)
            .apply { if (description != null) put("description", description) }
            .apply { if (enum != null) put("enum", JSONArray(enum)) }

    /**
     * Null when [name] is not this tool. [touchedReadThroughToolThisTurn] is [LiveToolbox.dispatch]'s
     * own argument, passed straight through so the remember gate cannot be lost by this file existing.
     */
    suspend fun dispatch(
        context: Context,
        name: String,
        args: JSONObject,
        touchedReadThroughToolThisTurn: Boolean,
    ): JSONObject? {
        if (name != TOOL_NAME) return null
        val action = args.optString("action")
        if (action == "remember" && LiveToolbox.rememberBlockedByReadThroughTool(touchedReadThroughToolThisTurn)) {
            return result(false, LiveToolbox.REMEMBER_MAIL_REFUSAL)
        }
        val controller = controllerFactory(context)
        return when (action) {
            "recall" -> recall(controller, args.optString("query"))
            "remember" -> remember(controller, args.optString("text"), args.optBoolean("shared", false))
            "forget" -> forget(controller, args.optString("id"))
            "list" -> list(controller)
            else -> result(false, "Say whether to recall, remember, forget or list. Nothing was done.")
        }
    }

    internal suspend fun recall(controller: MemoryController, query: String): JSONObject =
        when (val outcome = controller.recall(query)) {
            is MemoryOutcome.Ok -> {
                val read = outcome.value
                val out = JSONObject()
                    .put("success", true)
                    .put("count", read.memories.size)
                    .put("memories", memoriesJson(read.memories))
                    .put("source", if (read.fromCache) "phone_cache" else "engine")
                if (read.fromCache) out.put("note", MemoryWording.cachedNote(read))
                if (read.memories.isEmpty()) {
                    out.put("message", RECALL_EMPTY_NOTE)
                } else {
                    // Read at answer time, beside the memories it is about (2026-10-30).
                    out.put("how_to_use", LiveToolbox.RECALL_USE_NOTE)
                }
                out
            }
            else -> result(false, MemoryWording.readFailed(outcome))
        }

    internal suspend fun remember(controller: MemoryController, text: String, shared: Boolean): JSONObject =
        when (val outcome = controller.remember(text, shared, MemoryController.SOURCE_PHONE)) {
            is MemoryOutcome.Ok -> result(true, MemoryWording.saved(outcome.value)).put("id", outcome.value.id)
            else -> result(false, MemoryWording.writeFailed(outcome, "saved"))
        }

    internal suspend fun forget(controller: MemoryController, id: String): JSONObject {
        if (id.isBlank()) {
            return result(false, "Nothing was deleted. Recall or list first and pass the id of the memory to forget.")
        }
        return when (val outcome = controller.forget(id)) {
            is MemoryOutcome.Ok -> result(true, MemoryWording.DELETED)
            else -> result(false, MemoryWording.writeFailed(outcome, "deleted"))
        }
    }

    internal suspend fun list(controller: MemoryController): JSONObject =
        when (val outcome = controller.list()) {
            is MemoryOutcome.Ok -> {
                val read = outcome.value
                val total = read.memories.size
                val out = JSONObject()
                    .put("success", true)
                    .put("count", total)
                    .put("memories", memoriesJson(read.memories.take(LIST_SPOKEN)))
                    .put("source", if (read.fromCache) "phone_cache" else "engine")
                    .put(
                        "message",
                        when {
                            total == 0 -> "Nothing is kept about the user yet."
                            total > LIST_SPOKEN ->
                                "$total memories; these are the newest $LIST_SPOKEN. The Memory screen shows them all."
                            else -> "$total memories."
                        },
                    )
                if (read.fromCache) out.put("note", MemoryWording.cachedNote(read))
                out
            }
            else -> result(false, MemoryWording.readFailed(outcome))
        }

    private fun memoriesJson(memories: List<MemberMemory>): JSONArray {
        val arr = JSONArray()
        for (m in memories) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    // The day, then how it was learned (2026-11-09), so "you told me" is said only
                    // of a memory the person asked to keep.
                    .put("said", "(${MemoryWording.day(m.createdAtMs)}, ${MemoryWording.provenance(m)}) ${m.text}")
                    .apply { if (m.shared) put("shared", true) },
            )
        }
        return arr
    }

    /**
     * Rides on an empty recall: the sentence the old `recall_memory` description carried ("never
     * guess and never refuse"), moved here where it is billed per result instead of per setup.
     */
    internal const val RECALL_EMPTY_NOTE =
        "Nothing in memory matches. Say so in a clause and ask; never guess and never refuse."

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
