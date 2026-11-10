package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.watches.SOURCE_VOICE
import com.kevin.legion.watches.Watch
import com.kevin.legion.watches.WatchOutcome
import com.kevin.legion.watches.WatchWording
import com.kevin.legion.watches.WatchesController
import org.json.JSONArray
import org.json.JSONObject

/**
 * Standing watches by voice (ADR 0083): ONE `watch` tool with an action enum, the trade `clock`,
 * `memory` and `app_update` made, because [LiveSetupPayloadSizeTest] bills every declaration on
 * every reconnect. Every action calls [WatchesController], the controller the Watches screen calls
 * (ADR 0035). The kinds live in the engine's closed registry; a kind that fits nothing comes back
 * as the engine's refusal naming the kinds there are and the nearest one, billed per result.
 *
 * **A turn that read mail or the web cannot set or resume a watch** (round 3's per-turn
 * read-through flag, the one `remember` gates on, passed straight through from
 * [LiveToolbox.dispatch]): a watch is a durable write that later makes Alfred speak unprompted, so
 * third-party content must never become one. Pausing and removing are allowed in any turn: they
 * only make Alfred quieter.
 */
object WatchesToolbox {

    const val TOOL_NAME = "watch"

    internal val ACTIONS = listOf("list", "set", "pause", "resume", "remove")

    /** Test seam. Production is [WatchesController.forContext]. */
    internal var controllerFactory: (Context) -> WatchesController = WatchesController::forContext

    private const val DESCRIPTION =
        "Tell the user when something happens. set: kind charge_over, deposit_over, balance_under, " +
            "canvas_due, grade_posted, service_due or mail_reply; text is the merchant, payer, account, " +
            "course or companies. pause, resume, remove: text is the id from list."

    fun declarations(): JSONArray = JSONArray().put(
        fn(
            name = "watch",
            description = DESCRIPTION,
            params = JSONObject()
                .put("action", prop("string", ACTIONS))
                .put("kind", prop("string"))
                .put("dollars", prop("number"))
                .put("text", prop("string")),
            required = listOf("action"),
        ),
    )

    // `name = "..."` written out in the call above: tools/voice_guide.py and VoiceGuideDataTest find
    // tool names by scanning for that exact shape.
    private fun fn(name: String, description: String, params: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", params).put("required", JSONArray(required)),
            )

    private fun prop(type: String, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type).apply { if (enum != null) put("enum", JSONArray(enum)) }

    /** Null when [name] is not this tool, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(
        context: Context,
        name: String,
        args: JSONObject,
        touchedReadThroughToolThisTurn: Boolean,
    ): JSONObject? {
        if (name != TOOL_NAME) return null
        return run(controllerFactory(context), args, touchedReadThroughToolThisTurn)
    }

    /** One action, over the controller, so it is tested without Android. */
    internal suspend fun run(
        controller: WatchesController,
        args: JSONObject,
        readThroughThisTurn: Boolean,
    ): JSONObject {
        val action = args.optString("action")
        if (action in GUARDED && LiveToolbox.rememberBlockedByReadThroughTool(readThroughThisTurn)) {
            return result(false, WatchWording.READ_THROUGH_REFUSAL)
        }
        // pause, resume and remove take the id in `text` (one param fewer on every reconnect).
        val id = args.optString("id").ifBlank { args.optString("text") }.trim()
        return when (action) {
            "list" -> list(controller)
            "set" -> set(controller, args)
            "pause", "resume" -> if (id.isEmpty()) needId() else pause(controller, id, action == "pause")
            "remove" -> if (id.isEmpty()) needId() else remove(controller, id)
            else -> result(false, "Say list, set, pause, resume or remove. Nothing was changed.")
        }
    }

    /** The actions that make Alfred speak later, so a read-through turn may not take them. */
    private val GUARDED = setOf("set", "resume")

    private fun needId() = result(false, "Nothing was changed. List watches first and pass the id.")

    private suspend fun list(controller: WatchesController): JSONObject =
        when (val out = controller.list()) {
            is WatchOutcome.Ok -> result(true, WatchWording.listed(out.value.watches))
                .put("watches", JSONArray().apply { out.value.watches.forEach { put(watchJson(it)) } })
            else -> result(false, WatchWording.failed(out, "read"))
        }

    private suspend fun set(controller: WatchesController, args: JSONObject): JSONObject {
        val kind = args.optString("kind")
        if (kind.isBlank()) return result(false, "No watch was set: name a kind.")
        val amount = if (args.has("dollars") && !args.isNull("dollars")) args.optString("dollars") else null
        val text = args.optString("text").takeIf { it.isNotBlank() }
        return when (val out = controller.set(kind, amount, text, label = null, source = SOURCE_VOICE)) {
            is WatchOutcome.Ok -> result(true, WatchWording.set(out.value)).put("id", out.value.watch.id)
            else -> result(false, WatchWording.failed(out, "set"))
        }
    }

    private suspend fun pause(controller: WatchesController, id: String, paused: Boolean): JSONObject =
        when (val out = controller.pause(id, paused)) {
            is WatchOutcome.Ok -> result(true, WatchWording.paused(out.value))
            else -> result(false, WatchWording.failed(out, "changed"))
        }

    private suspend fun remove(controller: WatchesController, id: String): JSONObject =
        when (val out = controller.remove(id)) {
            is WatchOutcome.Ok -> result(true, WatchWording.removed(null))
            else -> result(false, WatchWording.failed(out, "changed"))
        }

    private fun watchJson(w: Watch): JSONObject = JSONObject()
        .put("id", w.id)
        .put("watch", w.label)
        .put("status", w.status)
        .apply { if (w.builtin != null) put("built_in", true) }

    private fun result(success: Boolean, message: String): JSONObject =
        JSONObject().put("success", success).put("message", message)
}
