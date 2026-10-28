package com.kevin.midnightai.diag

/**
 * The conversation audit (2026-10-24): what the driver said, what the companion said, every tool
 * call with its arguments, result and time taken, and how long each reply took to start. Written
 * to the drive log ONLY ([DriveLog.breadcrumb]), never through `MidnightEvents`, because that also
 * forwards to Crashlytics and transcripts must not leave for Google. The drive log goes to the
 * household's own engine, where transcripts are allowed (Kevin, 2026-10-15, see [LogRedactor]).
 *
 * Every line starts `audit ` so `manage.py device_logs --grep audit` pulls a drive's conversation.
 * Long text is cut at [MAX_CHARS]; a cut line says so.
 */
object ConvoAudit {
    const val MAX_CHARS = 600

    private val toolStartedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun heard(text: String, bytesForwarded: Long) {
        if (text.isNotBlank()) line("heard (${bytesForwarded}B): \"${clip(text)}\"")
    }

    fun said(text: String) {
        if (text.isNotBlank()) line("said: \"${clip(text)}\"")
    }

    /** [replyMs] is from the driver's last transcribed word to the companion's first audio, or
     * null when the turn had no driver speech (a proactive line) or no audio. */
    fun turnTiming(replyMs: Long?, toolCalls: Int) {
        line("turn: reply_ms=${replyMs ?: "n/a"} tool_calls=$toolCalls")
    }

    fun toolCall(id: String, name: String, args: String, nowMs: Long = System.currentTimeMillis()) {
        toolStartedAt[id] = nowMs
        line("tool_call: $name args=${clip(args)}")
    }

    fun toolResult(id: String, name: String, response: String, nowMs: Long = System.currentTimeMillis()) {
        val started = toolStartedAt.remove(id)
        val ms = if (started != null) "${nowMs - started}" else "unknown"
        line("tool_result: $name ms=$ms result=${clip(response)}")
    }

    fun toolCancelled(id: String, name: String) {
        toolStartedAt.remove(id)
        line("tool_cancelled: $name (the server dropped it; no answer was sent)")
    }

    internal fun clip(text: String): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= MAX_CHARS) flat else flat.take(MAX_CHARS) + " [cut, ${flat.length} chars]"
    }

    private fun line(message: String) = runCatching { DriveLog.breadcrumb("audit $message") }
}
