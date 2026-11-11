package com.kevin.legion.service

import com.kevin.legion.service.conversationlog.ConversationLog

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
 * Writes through [sink], which is [LiveLatency.audit] in production (logcat and/or the engine spool,
 * never the on-screen probe buffer). Off ([enabled] false) means no line at all. [onSession] hears
 * every session start and end whatever [enabled] says, so the engine spool can seal and upload.
 * Thread-safe: the socket thread and the tool dispatcher both call in.
 */
@Suppress("TooManyFunctions") // one function per LIVE_AUDIT event, the format's whole surface
internal class LiveAudit(
    private val sink: (String) -> Unit = { LiveLatency.audit(TAG, it) },
    private val isReadThroughTool: (String) -> Boolean = { GeminiLiveSession.isEpisodicExcludedTool(it) },
    private val onSession: (Boolean) -> Unit = { ConversationLog.onSession(it) },
) {
    @Volatile var enabled = false

    private val toolNames = HashMap<String, String>()
    private var readThroughToolThisTurn = false
    private var redactionLoggedThisTurn = false
    private val readThroughLoggedThisTurn = HashSet<String>()

    /**
     * [pack] is the Alfred pack the session's prompt was built from, `<version>/<remote|cache|bundled>`
     * (alfred-ota ticket 02), so every conversation record says which wording it ran. Null leaves
     * the token off, as before the pack existed.
     */
    @Synchronized
    fun sessionStart(model: String, asyncSet: Set<String>, pack: String? = null) {
        toolNames.clear()
        resetTurn()
        val async = asyncSet.sorted().joinToString(",").ifEmpty { "none" }
        val packToken = pack?.let { " pack=${token(it)}" }.orEmpty()
        emit("session_start model=${token(model)} async_set=$async$packToken")
        onSession(true)
    }

    @Synchronized
    fun sessionEnd(reason: String) {
        emit("session_end reason=${quote(reason)}")
        onSession(false)
    }

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
    fun toolCall(id: String, name: String, async: Boolean, argsJson: String, via: String? = null) {
        toolNames[id] = name
        if (isReadThroughTool(name)) readThroughToolThisTurn = true
        // ADR 0086: an email's body never reaches this log; EmailToolbox.redactArgs keeps its length.
        val logged = EmailToolbox.redactArgs(name, argsJson)
        // `via` (live cost diet, 2026-11-10): the action tool the model called when [name] is the
        // tool it resolved to. Before args, which stays the last field.
        val viaField = via?.let { " via=${token(it)}" } ?: ""
        emit("tool_call id=${token(id)} name=${token(name)} async=$async$viaField args=${json(logged)}")
    }

    /**
     * Voice cost audit 2026-11-10, F7a: one line per `usageMetadata` message, raw (not folded),
     * counts and modality names only ([com.kevin.legion.ai.LiveUsage.auditFields]). Raw on purpose:
     * how the reports line up against turn_complete and tool_call lines is what settles whether
     * Live bills per generation, per turn, or cumulatively.
     */
    fun usage(fields: String) = emit("usage $fields")

    /** The scripted opening line played on a tap or wake word (F2): a clip id, or `earcon[:why]`. */
    fun listenCue(label: String) = emit("listen_cue clip=${token(label)}")

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

    /**
     * The turn was just marked read-through (`remember` refused for the rest of it), and why:
     * [source] is the read-through tool's name, `grounding` (the Live model's own Google Search),
     * `fleet_web` (a sub-agent behind [tool] got web text back, [com.kevin.legion.ai.WebReadMark]),
     * `proactive` (a timed raise carrying third-party text) or `carry` (a NON_BLOCKING web result
     * spoken in this turn, marked in the last). Names only: never a query, URL or any content. Once
     * per source and tool per turn: grounding metadata can ride on many messages of one turn.
     */
    @Synchronized
    fun readThrough(source: String, tool: String? = null) {
        val line = "read_through source=${token(source)}" + (tool?.let { " tool=${token(it)}" } ?: "")
        if (readThroughLoggedThisTurn.add(line)) emit(line)
    }

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
        readThroughLoggedThisTurn.clear()
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

        /** A Mapbox public or secret token (`pk.eyJ...` / `sk.eyJ...`); a router error can echo its URL (NAV_AUDIT). */
        private val MAPBOX_TOKEN = Regex("\\b[ps]k\\.eyJ[A-Za-z0-9._\\-]+")
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
            .replace(MAPBOX_TOKEN, "[key]")
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
