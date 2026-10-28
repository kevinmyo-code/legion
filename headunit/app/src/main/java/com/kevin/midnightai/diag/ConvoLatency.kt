package com.kevin.midnightai.diag

/**
 * Per-turn timing for the head unit's Live voice loop, written as `LIVE_LATENCY` lines beside the
 * [ConvoAudit] lines (2026-10-28, phone parity). Pure bookkeeping: callers pass the clock in, so a
 * JVM test drives it. `GeminiLiveSession` owns one per session.
 *
 * Line formats are the phone's (`app/.../service/LiveLatency.kt`, read by `tools/voice_latency.py`
 * and `tools/voice_audit.py`): `cold_start_ms=<n>`, `reply_ms=<n>`, and
 * `tool_ms=<n> tool=<name> sent=<bool> async=false`.
 *
 * - **cold start**: [start] to setupComplete.
 * - **reply**: the LAST inputTranscription chunk to the first model audio chunk. A turn with no
 *   transcription (a proactive line) has no start mark and reports nothing.
 * - **tool**: toolCall received to toolResponse sent, per call id, with the tool's name.
 *
 * **One deliberate difference from the phone:** a turn that ends with no audio (the silent call
 * turn of a tool round trip) keeps the reply start mark, so the spoken answer's `reply_ms` spans the
 * whole round trip (landed 2026-10-24, `b1f4b5cf`). The phone clears it on every turnComplete. The
 * line's shape is the same; on a tool turn the head unit's figure is the longer one.
 */
class ConvoLatency(
    private val sink: (String) -> Unit = { DriveLog.conversation(TAG, it) },
) {
    private var startAtMs = 0L
    private var lastInputAtMs = 0L
    private val toolStarts = HashMap<String, Pair<String, Long>>()

    @Synchronized
    fun start(nowMs: Long) {
        startAtMs = nowMs
        lastInputAtMs = 0L
        toolStarts.clear()
    }

    /** Writes `cold_start_ms`, once per [start]. */
    @Synchronized
    fun setupComplete(nowMs: Long) {
        elapsed(startAtMs, nowMs)?.let { emit("cold_start_ms=$it") }
        startAtMs = 0L
    }

    @Synchronized
    fun inputTranscript(nowMs: Long) {
        lastInputAtMs = nowMs
    }

    /** The turn's first audio chunk: writes `reply_ms` when a transcript chunk preceded it. */
    @Synchronized
    fun firstAudio(nowMs: Long) {
        elapsed(lastInputAtMs, nowMs)?.let { emit("reply_ms=$it") }
        lastInputAtMs = 0L
    }

    /**
     * A transcript chunk that lands after the reply started must not seed the next turn, so a turn
     * that played audio clears the mark. A turn with no audio (a tool call turn) keeps it.
     */
    @Synchronized
    fun turnComplete(hadAudio: Boolean) {
        if (hadAudio) lastInputAtMs = 0L
    }

    @Synchronized
    fun toolCall(id: String, name: String, nowMs: Long) {
        toolStarts[id] = name to nowMs
    }

    /** Writes `tool_ms` and returns the duration, or -1 for an id that was never seen. */
    @Synchronized
    fun toolResponse(id: String, sent: Boolean, nowMs: Long): Long {
        val (name, at) = toolStarts.remove(id) ?: return -1L
        val ms = (nowMs - at).coerceAtLeast(0L)
        emit("tool_ms=$ms tool=${ConvoAudit.token(name)} sent=$sent async=false")
        return ms
    }

    private fun elapsed(fromMs: Long, nowMs: Long): Long? =
        if (fromMs > 0L) (nowMs - fromMs).coerceAtLeast(0L) else null

    private fun emit(line: String) {
        runCatching { sink(line) }
    }

    companion object {
        const val TAG = "LIVE_LATENCY"
    }
}
