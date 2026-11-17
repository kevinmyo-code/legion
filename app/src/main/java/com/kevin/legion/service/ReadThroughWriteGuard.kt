package com.kevin.legion.service

import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.service.tools.ToolReads
import org.json.JSONObject

/**
 * ADR 0090 on the phone (2026-11-17): **no write follows outside text in the same turn.** The twin
 * of the engine's write guard (`server/agents/legion/guards.py`, `tool.writes and state.tainted`):
 * one check, before every resolved tool call on every path (Live, the cascade on cloud or laptop
 * voice, typed chat), in `LiveSessionController.runTool`.
 *
 * In a turn marked read-through (mail, a web page or search, the brief, a camera look, a GitHub
 * result through `ask_engine`, or the carry turn after one), a call runs only if [ToolReads] lists
 * it as a read. Everything else (a write, a call, a message, a control, a screen, anything new) is
 * refused in words and nothing runs; the change waits for the person's next message. That covers
 * the model-set `confirmed` tools (garage, call, forget a place, app install), because a model can
 * set that flag in the same turn it read the text.
 *
 * The per-tool checks inside `memory`, `watch`, `claude_session`, `manage_item` (a proposed to-do)
 * and `generate_goal_plan` stay as a second lock for callers that reach [LiveToolbox.dispatch]
 * directly; on the three voice and typed paths this guard answers first.
 */
object ReadThroughWriteGuard {

    /** The engine's `read_through.WRITE_REFUSAL`, with the phone's own sources named. */
    const val WRITE_REFUSAL = "Nothing was changed: %s did not run. This turn read outside text (mail, a web " +
        "page or search, the brief's news, a camera look or a GitHub result), which can carry instructions " +
        "that are not the person's, so changes wait for their next message. Say so; they can simply ask again."

    /**
     * The refusal for [wireName]/[wireArgs] (as the model sent it; resolved here) when [readThrough]
     * is set and the call is not a read, else null. [readThrough] is the turn's mark as it stood
     * before this call's own mark, so a read-through tool that also acts does not refuse itself.
     */
    fun refusal(wireName: String, wireArgs: JSONObject, readThrough: Boolean): JSONObject? {
        val (name, args) = LiveSurface.resolve(wireName, wireArgs)
        if (!readThrough || ToolReads.isRead(name, args)) return null
        // A memory keeps the words it always had (the engine special-cases `remember` the same way).
        val words = if (name == MemoryToolbox.TOOL_NAME && args.optString("action") == "remember") {
            LiveToolbox.REMEMBER_MAIL_REFUSAL
        } else {
            WRITE_REFUSAL.format(name)
        }
        return JSONObject()
            .put("success", false)
            .put("read_through", true)
            .put("tool", name)
            .put("message", words)
    }

    /**
     * Whether a call, as the model SENT it, reads outside text: a folded action tool (`look_up`
     * with `domain: ask_mail`, `day` with `get_sitrep`, `track_package` or `flight_status`) is
     * judged on the tool it stands for ([LiveSurface.resolve]) against
     * [LiveToolbox.EPISODIC_EXCLUDED_TOOLS], the way Live and typed already judge it. The cascade's
     * taint check goes through this (2026-11-17, gap 1).
     */
    fun isReadThroughCall(name: String, args: JSONObject): Boolean =
        GeminiLiveSession.isEpisodicExcludedTool(LiveSurface.resolve(name, args).name)
}
