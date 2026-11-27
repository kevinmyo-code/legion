package com.kevin.legion.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * LIVE_AUDIT lines for TYPED chat (conversation-records ticket 03: "typed-chat turns included").
 * Typed chat runs on a text model ([TypedChat]), never the Live session, so [GeminiLiveSession]'s
 * own [LiveAudit] never sees it. This feeds a second [LiveAudit] the same events in the same
 * format: `user final=true typed=true`, each `tool_call`/`tool_result`, the reply as one
 * `assistant` line, then `turn_complete`. Its lines reach logcat and the engine spool through the
 * same sink as voice ([LiveLatency.audit]).
 *
 * **CLAUDE.md sec 7 holds by construction:** [LiveAudit] redacts a read-through tool's result and
 * every assistant line after it, and [TypedTurnRecord.readThrough] redacts the reply too.
 *
 * [enabled] is read at each user line, so a settings change applies from the next typed message.
 */
internal class TypedChatAudit(
    private val enabled: () -> Boolean,
    private val audit: LiveAudit = LiveAudit(onSession = {}),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val ids = AtomicLong()
    private val started = ConcurrentHashMap<String, Long>()

    // Typed chat has no session to mark a conversation's edges, so a gap does (memory chain ticket
    // 450): a message sent this long after the last one begins a new chat for the saved turns.
    @Volatile private var lastUserAt: Long? = null

    fun user(text: String) {
        audit.enabled = enabled()
        val at = now()
        val last = lastUserAt
        if (last == null || at - last > NEW_CHAT_GAP_MS) audit.startChat()
        lastUserAt = at
        audit.user(text, final = true, typed = true)
    }

    /** Logs the call and returns the id [toolResult] closes it with. */
    fun toolCall(name: String, args: JSONObject): String {
        val id = "typed-${ids.incrementAndGet()}"
        started[id] = now()
        audit.toolCall(id, name, async = false, argsJson = args.toString())
        return id
    }

    fun toolResult(id: String, name: String, response: JSONObject) {
        val ms = started.remove(id)?.let { (now() - it).coerceAtLeast(0L) } ?: 0L
        val success = LiveAudit.successOf(
            hasSuccess = response.has("success"),
            success = response.optBoolean("success"),
            hasError = response.has("error"),
        )
        audit.toolResult(
            LiveAudit.ToolResult(id, name, success, ms, async = false, sent = true, responseJson = response.toString()),
        )
    }

    /** The typed turn was marked read-through: `read_through source=<tool|fleet_web> [tool=<name>]`. */
    fun readThrough(source: String, tool: String?) = audit.readThrough(source, tool)

    fun turn(record: TypedTurnRecord) {
        if (record.replyText.isNotBlank()) audit.assistant(record.replyText, readThrough = record.readThrough)
        audit.turnComplete()
    }

    private companion object {
        const val NEW_CHAT_GAP_MS = 30 * 60_000L
    }
}
