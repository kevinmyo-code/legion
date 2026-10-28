package com.kevin.midnightai.diag

/**
 * The head unit's conversation audit (2026-10-24; phone parity 2026-10-28, Kevin: "all logs on
 * engine. both headunit and phone"). One `LIVE_AUDIT` line per conversation event: what the driver
 * said, what the companion said, what each tool was asked and returned.
 *
 * **The line set and format are the phone's** (`app/.../service/LiveAudit.kt`, async-lookups ticket
 * 05), so one `tools/voice_audit.py` and the engine's `manage.py device_logs --render` read both
 * devices. Change all three together. `<event> key=value ...`, the one free-text field always LAST;
 * text is scrubbed of credential shapes, cut at [MAX_TEXT] characters with a `...` marker, then
 * JSON-quoted so a newline or a quote in speech cannot break a line. `args=` and `response=` are
 * compact JSON, scrubbed and cut the same way, newline-free.
 *
 * Head-unit differences, all in values never in shape: `async=false` and `scheduling=none` always
 * (no non-blocking tools here); `activity_start`/`activity_end` are the SERVER's `voiceActivity`
 * events (this unit runs server VAD and sends no activity of its own); `interrupted source=barge_in`
 * is the driver's talk tap over the companion.
 *
 * **Where it goes.** Through [sink], which is [DriveLog.conversation] in production: a
 * logcat-shaped line in the conversation spool on disk, uploaded to the household's own engine as
 * `reason=conversation`. Never logcat (a unit whose logcat is unreadable would lose it) and never
 * `MidnightEvents`, which forwards to Crashlytics: transcripts must not leave for Google. Transcripts
 * on the household's engine are allowed (Kevin, 2026-10-15, [LogRedactor]).
 *
 * **CLAUDE.md sec 7, third-party content is read-through only.** The phone redacts a turn that ran
 * a mail tool. This app has no mail tool and the engine's MCP exposes none (`engine_mcp/tools.py`:
 * "mail never reaches the server", pinned by its test), so [isReadThroughTool] matches nothing
 * today. The mechanism is the phone's, so a read-through tool added later only has to be named.
 */
@Suppress("TooManyFunctions") // one function per LIVE_AUDIT event, the format's whole surface
class ConvoAudit(
    private val sink: (String) -> Unit = { DriveLog.conversation(TAG, it) },
    private val isReadThroughTool: (String) -> Boolean = { it in READ_THROUGH_TOOLS },
) {
    @Volatile var enabled = true

    private val toolNames = HashMap<String, String>()
    private var readThroughToolThisTurn = false
    private var redactionLoggedThisTurn = false

    @Synchronized
    fun sessionStart(model: String, asyncSet: Set<String> = emptySet()) {
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

    @Synchronized
    fun assistant(chunk: String, readThrough: Boolean = false) {
        if (readThrough || readThroughToolThisTurn) {
            if (!redactionLoggedThisTurn) emit("assistant text=${quote(REDACTED)}")
            redactionLoggedThisTurn = true
        } else {
            emit("assistant text=${quote(chunk)}")
        }
    }

    @Synchronized
    fun toolCall(id: String, name: String, argsJson: String, async: Boolean = false) {
        toolNames[id] = name
        if (isReadThroughTool(name)) readThroughToolThisTurn = true
        emit("tool_call id=${token(id)} name=${token(name)} async=$async args=${json(argsJson)}")
    }

    /** `sent=false` is a response the socket refused, or one held back after a cancellation. */
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

    /** [source] is `server` (the Live API's `interrupted`) or `barge_in` (the driver's talk tap). */
    @Synchronized
    fun interrupted(source: String) = emit("interrupted source=${token(source)}")

    @Synchronized
    fun activityStart() = emit("activity_start")

    @Synchronized
    fun activityEnd() = emit("activity_end")

    @Synchronized
    fun micOpen(reason: String) = emit("mic_open reason=${token(reason)}")

    @Synchronized
    fun micClosed(reason: String) = emit("mic_closed reason=${token(reason)}")

    private fun resetTurn() {
        readThroughToolThisTurn = false
        redactionLoggedThisTurn = false
    }

    private fun emit(line: String) {
        if (enabled) runCatching { sink(line) }
    }

    /** One tool response as it went back to the socket (or did not). */
    data class ToolResult(
        val id: String,
        val name: String,
        val success: Boolean,
        val ms: Long,
        val async: Boolean = false,
        val sent: Boolean,
        val responseJson: String,
    )

    companion object {
        const val TAG = "LIVE_AUDIT"
        const val MAX_TEXT = 400
        const val REDACTED = "[redacted: third-party content]"

        /** The head unit's `EPISODIC_EXCLUDED_TOOLS`: empty, it has no mail tool (class doc). */
        val READ_THROUGH_TOOLS: Set<String> = emptySet()

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
