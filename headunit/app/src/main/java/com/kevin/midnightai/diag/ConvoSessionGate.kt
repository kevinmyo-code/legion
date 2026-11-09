package com.kevin.midnightai.diag

/**
 * Keeps a Live session that nobody spoke in out of the conversation spool (2026-11-09, prod
 * device_logs audit: 182 `conversation` records from the head unit, every one a `session_start`,
 * a `cold_start_ms` and a `session_end` with no user or assistant line between them).
 *
 * One gate per `GeminiLiveSession`, in front of BOTH its [ConvoAudit] and its [ConvoLatency]
 * sinks: the latency line `cold_start_ms` lands before anyone speaks, so gating the audit alone
 * would still spool and upload a batch per empty session.
 *
 * From `session_start` it HOLDS every line, with the time it was written. The first line that is
 * a spoken turn (a `user` or `assistant` line, or a `tool_call`, which only a turn produces) flushes
 * everything held, in order and with the original times, and from then on lines pass straight
 * through. A `session_end` reached with nothing spoken drops what was held, `session_end`
 * included, and anything that trails in after it, so the spool gets nothing and the upload that
 * follows a session end has nothing to seal. The session-end reason is still a breadcrumb in the
 * drive log (`MidnightEvents.sessionEnd`).
 *
 * The hold is bounded at [maxHeld] lines: a session that fills it without a turn (a noisy VAD
 * firing `activity_start` for minutes) is flushed rather than kept in memory, so nothing real is
 * lost to the bound. Lines with no `session_start` before them pass through unchanged.
 *
 * It recognises lines by the leading event word of the [ConvoAudit] formats, which
 * `ConvoAuditTest` pins verbatim.
 */
class ConvoSessionGate(
    private val sink: (tag: String, line: String, atMs: Long) -> Unit = DriveLog::conversationAt,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxHeld: Int = MAX_HELD,
) {
    private enum class State { PASS, HOLDING, DROPPING }

    private data class Held(val tag: String, val line: String, val atMs: Long)

    private var state = State.PASS
    private val held = ArrayList<Held>()

    @Synchronized
    fun write(tag: String, line: String) {
        val at = now()
        val event = if (tag == ConvoAudit.TAG) line.substringBefore(' ') else ""
        if (event == SESSION_START) {
            // A new session: whatever an unfinished empty one still held is dropped with it.
            held.clear()
            held += Held(tag, line, at)
            state = State.HOLDING
            return
        }
        when (state) {
            State.PASS -> emit(tag, line, at)
            State.DROPPING -> Unit
            State.HOLDING -> when {
                event in SPOKEN -> {
                    flush()
                    emit(tag, line, at)
                }
                event == SESSION_END -> {
                    held.clear()
                    state = State.DROPPING
                }
                else -> {
                    held += Held(tag, line, at)
                    if (held.size >= maxHeld) flush()
                }
            }
        }
    }

    private fun flush() {
        held.forEach { emit(it.tag, it.line, it.atMs) }
        held.clear()
        state = State.PASS
    }

    private fun emit(tag: String, line: String, atMs: Long) {
        runCatching { sink(tag, line, atMs) }
    }

    companion object {
        const val MAX_HELD = 200
        private const val SESSION_START = "session_start"
        private const val SESSION_END = "session_end"

        /** The events that mean a turn happened: someone spoke, or the model acted on a turn. */
        private val SPOKEN = setOf("user", "assistant", "tool_call")
    }
}
