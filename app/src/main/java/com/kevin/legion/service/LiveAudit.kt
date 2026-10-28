package com.kevin.legion.service

/**
 * The conversation audit log (async-lookups ticket 05, 2026-10-28, Kevin: "cant u like record what
 * i say in the logs too so u can see everything and audit"). One `LIVE_AUDIT` logcat line per
 * conversation event: what the user said, what the assistant said, what each tool was asked and
 * returned. The `fast` build is release-shaped, so Room is not readable over ADB; this is how a run
 * is audited. `tools/voice_audit.py` reads it, so change both together.
 *
 * Format: `<event> key=value ...`, with the one free-text field always LAST, so a parser can take
 * the fixed keys as `\S+` tokens and the rest of the line as the text. Text is truncated at
 * [MAX_TEXT] characters with a `...` marker, then quoted with JSON escaping (so a newline or a quote
 * in speech cannot break a line). `args=` and `response=` are compact JSON, truncated the same way,
 * newline-free. [scrub] runs on every free-text field first: no API key, token or Authorization
 * header ever reaches the log.
 *
 * **CLAUDE.md sec 7, third-party content is read-through only.** On a turn that ran a tool in
 * [LiveToolbox.EPISODIC_EXCLUDED_TOOLS] (mail) or that the session marked read-through (a timed
 * sitrep), every assistant chunk from that point on is replaced by ONE
 * `assistant text="[redacted: third-party content]"` line, and that tool's own `tool_result` carries
 * `response="[redacted: third-party content]"`. Chunks spoken before the mail tool was asked for
 * stay in clear: no mail content exists yet when they are logged. The user's own words always log.
 *
 * Writes through [sink], which is [LiveLatency.logcat] in production (logcat only, never the
 * on-screen probe buffer). Off ([enabled] false) means no line at all. Thread-safe: the socket thread and the tool
 * dispatcher both call in.
 */
@Suppress("TooManyFunctions") // one function per LIVE_AUDIT event, the format's whole surface
internal class LiveAudit(
    private val sink: (String) -> Unit = { LiveLatency.logcat(TAG, it) },
    private val isReadThroughTool: (String) -> Boolean = { GeminiLiveSession.isEpisodicExcludedTool(it) },
) {
    @Volatile var enabled = false

    private val toolNames = HashMap<String, String>()
    private var readThroughToolThisTurn = false
    private var redactionLoggedThisTurn = false

    @Synchronized
    fun sessionStart(model: String, asyncSet: Set<String>) {
        toolNames.clear()
        resetTurn()
        val async = asyncSet.sorted().joinToString(",").ifEmpty { "none" }
        emit("session_start model=${token(model)} async_set=$async")
    }

    @Synchronized
    fun sessionEnd(reason: String) = emit("session_end reason=${quote(reason)}")

    /** [final] is the transcription's own `finished` flag, or true for a whole-turn line. */
    @Synchronized
    fun user(text: String, final: Boolean, typed: Boolean = false) =
        emit("user final=$final" + (if (typed) " typed=true" else "") + " text=${quote(text)}")

    /** [readThrough] is the session's own per-turn flag (`mailToolCalledThisTurn`). */
    @Synchronized
    fun assistant(chunk: String, readThrough: Boolean) {
        if (readThrough || readThroughToolThisTurn) {
            if (!redactionLoggedThisTurn) emit("assistant text=${quote(REDACTED)}")
            redactionLoggedThisTurn = true
        } else {
            emit("assistant text=${quote(chunk)}")
        }
    }

    @Synchronized
    fun toolCall(id: String, name: String, async: Boolean, argsJson: String) {
        toolNames[id] = name
        if (isReadThroughTool(name)) readThroughToolThisTurn = true
        emit("tool_call id=${token(id)} name=${token(name)} async=$async args=${json(argsJson)}")
    }

    /**
     * `scheduling=WHEN_IDLE` when the result went back non-blocking (ADR 0065), else `none`.
     * `sent=false` is a response the socket dropped, or one held back after a cancellation.
     */
    @Synchronized
    fun toolResult(result: ToolResult) {
        toolNames.remove(result.id)
        val body = if (isReadThroughTool(result.name)) quote(REDACTED) else json(result.responseJson)
        emit(
            "tool_result id=${token(result.id)} name=${token(result.name)} success=${result.success} " +
                "ms=${result.ms} scheduling=${if (result.async) "WHEN_IDLE" else "none"} " +
                "sent=${result.sent} response=$body",
        )
    }

    @Synchronized
    fun toolCancelled(id: String) =
        emit("tool_cancelled id=${token(id)} name=${token(toolNames[id] ?: "unknown")}")

    /** Logs the turn's end, then clears the per-turn read-through state. */
    @Synchronized
    fun turnComplete() {
        emit("turn_complete")
        resetTurn()
    }

    /** [source] is `server` (the Live API's `interrupted`) or `barge_in` (the phone's own gate). */
    @Synchronized
    fun interrupted(source: String) = emit("interrupted source=${token(source)}")

    @Synchronized
    fun activityStart() = emit("activity_start")

    @Synchronized
    fun activityEnd() = emit("activity_end")

    /**
     * A system note the session sent the model mid-lookup (async-lookups ticket 07): [reason] is
     * `still_checking` (with [afterMs], how long the call had run) or `late_holding_line`.
     */
    @Synchronized
    fun nudge(reason: String, tool: String, afterMs: Long? = null) =
        emit("nudge reason=${token(reason)} tool=${token(tool)}" + (afterMs?.let { " after_ms=$it" } ?: ""))

    @Synchronized
    fun micOpen(reason: String) = emit("mic_open reason=${token(reason)}")

    @Synchronized
    fun micClosed(reason: String) = emit("mic_closed reason=${token(reason)}")

    private fun resetTurn() {
        readThroughToolThisTurn = false
        redactionLoggedThisTurn = false
    }

    private fun emit(line: String) {
        if (enabled) sink(line)
    }

    /** One tool response as it went back to the socket. */
    data class ToolResult(
        val id: String,
        val name: String,
        val success: Boolean,
        val ms: Long,
        val async: Boolean,
        val sent: Boolean,
        val responseJson: String,
    )

    companion object {
        const val TAG = "LIVE_AUDIT"
        const val MAX_TEXT = 400
        const val REDACTED = "[redacted: third-party content]"

        private val GOOGLE_KEY = Regex("AIza[0-9A-Za-z_\\-]{20,}")
        private val BEARER = Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=\\-]+")
        private val JSON_SECRET = Regex(
            "(?i)(\"(?:api_?key|key|token|access_token|refresh_token|id_token|authorization|password|secret)\"" +
                "\\s*:\\s*)\"[^\"]*\"",
        )
        private val QUERY_SECRET = Regex("(?i)([?&](?:key|token|access_token)=)[^&\\s\"]+")
        private val WHITESPACE = Regex("\\s+")

        /** `success` from the response's own field, else true unless it carries an `error`. */
        fun successOf(hasSuccess: Boolean, success: Boolean, hasError: Boolean): Boolean =
            if (hasSuccess) success else !hasError

        /** Removes anything shaped like a credential. Runs before truncation. */
        fun scrub(text: String): String = text
            .replace(GOOGLE_KEY, "[key]")
            .replace(BEARER, "Bearer [redacted]")
            .replace(JSON_SECRET, "$1\"[redacted]\"")
            .replace(QUERY_SECRET, "$1[redacted]")

        /** At most [MAX_TEXT] characters, then `...` when anything was cut. */
        fun truncate(text: String): String = if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "..." else text

        /** Scrubbed, truncated, and JSON-quoted: always one line, always parseable by `json.loads`. */
        fun quote(text: String): String {
            val s = truncate(scrub(text))
            val out = StringBuilder(s.length + 2).append('"')
            for (c in s) out.append(escape(c))
            return out.append('"').toString()
        }

        private fun escape(c: Char): String = when (c) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> if (c < ' ' || c == '\u2028' || c == '\u2029') "\\u%04x".format(c.code) else c.toString()
        }

        /** A compact JSON field: scrubbed, truncated, never containing a line break. */
        fun json(raw: String): String = truncate(scrub(raw))
            .replace("\r", "\\r").replace("\n", "\\n").replace('\u2028', ' ').replace('\u2029', ' ')

        /** A `key=value` value: no whitespace, never empty (the parser reads `\S+`). */
        fun token(value: String): String = value.trim().replace(WHITESPACE, "_").ifEmpty { "none" }
    }
}
