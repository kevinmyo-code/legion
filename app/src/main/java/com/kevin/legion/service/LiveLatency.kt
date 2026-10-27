package com.kevin.legion.service

import android.util.Log
import com.kevin.legion.car.CarProbeLog

/**
 * Per-turn timing for the Live voice loop (2026-10-27, Kevin: the voice agent feels slow). Pure
 * bookkeeping: callers pass the clock in, so a JVM test can drive it. [GeminiLiveSession] owns
 * one per session and writes what it returns through [log].
 *
 * - **cold start**: [start] to setupComplete.
 * - **reply**: the LAST inputTranscription chunk of a turn to the first model audio chunk. A turn
 *   with no transcription (a typed turn, a proactive line) has no start mark and reports nothing.
 *   A tool call inside the turn is part of this figure; its own share is the tool line.
 * - **tool**: toolCall received to toolResponse sent, per call id, with the tool's name.
 *
 * Line format is `key_ms=<n>` plus `tool=`/`model=` where they apply (LiveToolbox.askEngine adds
 * `ask_engine_ms=<n> model=<id>`). `tools/voice_latency.py` reads a `adb logcat -s LIVE_LATENCY`
 * capture and prints count, p50, p95 and max per metric, so change both together.
 */
internal class LiveLatency {
    private var startAtMs = 0L
    private var lastInputAtMs = 0L
    private val toolStarts = HashMap<String, Pair<String, Long>>()

    @Synchronized
    fun start(nowMs: Long) {
        startAtMs = nowMs
        lastInputAtMs = 0L
        toolStarts.clear()
    }

    /** Milliseconds since [start], once per [start]; null if there was no start mark. */
    @Synchronized
    fun setupComplete(nowMs: Long): Long? = elapsed(startAtMs, nowMs).also { startAtMs = 0L }

    @Synchronized
    fun inputTranscript(nowMs: Long) {
        lastInputAtMs = nowMs
    }

    /** Milliseconds since the last transcript chunk, consumed so a turn reports once. */
    @Synchronized
    fun firstAudio(nowMs: Long): Long? = elapsed(lastInputAtMs, nowMs).also { lastInputAtMs = 0L }

    /** A transcript chunk that lands after the reply started must not seed the next turn. */
    @Synchronized
    fun turnComplete() {
        lastInputAtMs = 0L
    }

    @Synchronized
    fun toolCall(id: String, name: String, nowMs: Long) {
        toolStarts[id] = name to nowMs
    }

    /** The tool's name and its duration, or null for an id that was never seen. */
    @Synchronized
    fun toolResponse(id: String, nowMs: Long): Pair<String, Long>? =
        toolStarts.remove(id)?.let { (name, at) -> name to (nowMs - at).coerceAtLeast(0L) }

    private fun elapsed(fromMs: Long, nowMs: Long): Long? =
        if (fromMs > 0L) (nowMs - fromMs).coerceAtLeast(0L) else null

    companion object {
        const val TAG = "LIVE_LATENCY"

        /**
         * Writes one line to BOTH sinks: [CarProbeLog] is in-memory only and readable on the
         * screen, and the A25 needs logcat. Used for LIVE_LATENCY and LIVE_FOLLOWUP alike.
         */
        fun log(tag: String, message: String) {
            CarProbeLog.log(tag, message)
            Log.i(tag, message)
        }
    }
}
