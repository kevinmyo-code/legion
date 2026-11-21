package com.kevin.legion.service.cascade

/**
 * One turn's sounds before the answer, for the bench ([DeadAir]): his end of speech, then each filler
 * clip (backchannel, task line, wait line) from its first write to its drain, then the answer's first
 * audio. [line] is the longest silent stretch in that window and what filled it, numbers and fixed
 * tokens only, in the LIVE_LATENCY style:
 * `gap_max_ms=<n> gap_after=<eot|backchannel|tool|wait> fillers=<kind+kind|none> answer_ms=<n>`.
 * A clip still playing when the answer's audio is in covers the time up to it. Thread-safe.
 */
internal class TurnGap(private val eotAtMs: Long) {
    private class Sound(val kind: String, val startMs: Long, var endMs: Long?)

    private val sounds = mutableListOf<Sound>()
    private var logged = false

    /** A filler clip of [kind] started at [atMs]; the returned id ends it ([ended]). */
    @Synchronized
    fun started(kind: String, atMs: Long): Int {
        sounds += Sound(kind, atMs, null)
        return sounds.size - 1
    }

    @Synchronized
    fun ended(id: Int, atMs: Long) {
        sounds.getOrNull(id)?.endMs = atMs
    }

    /** The line at the answer's first audio [answerAtMs]; null the second time (one line a turn). */
    @Synchronized
    fun line(answerAtMs: Long): String? {
        if (logged) return null
        logged = true
        var cursor = eotAtMs
        var after = EOT
        var maxGap = 0L
        var maxAfter = EOT
        for (s in sounds.filter { it.startMs < answerAtMs }.sortedBy { it.startMs }) {
            val gap = s.startMs - cursor
            if (gap > maxGap) {
                maxGap = gap
                maxAfter = after
            }
            val end = minOf(s.endMs ?: answerAtMs, answerAtMs)
            if (end > cursor) {
                cursor = end
                after = s.kind
            }
        }
        val last = answerAtMs - cursor
        if (last > maxGap) {
            maxGap = last
            maxAfter = after
        }
        val fillers = sounds.filter { it.startMs < answerAtMs }.joinToString("+") { it.kind }.ifEmpty { "none" }
        return "gap_max_ms=$maxGap gap_after=$maxAfter fillers=$fillers answer_ms=${answerAtMs - eotAtMs}"
    }

    companion object {
        const val EOT = "eot"
        const val BACKCHANNEL = "backchannel"
        const val TOOL = "tool"
        const val WAIT = "wait"
    }
}
