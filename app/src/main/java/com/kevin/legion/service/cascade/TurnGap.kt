package com.kevin.legion.service.cascade

/**
 * One turn's sounds before the answer, for the bench ([DeadAir]): his end of speech, then each filler
 * clip (backchannel, task line, wait line) from its first write to its drain, then the answer's first
 * audio. [line] is the longest silent stretch in that window and what filled it, numbers and fixed
 * tokens only, in the LIVE_LATENCY style:
 * `gap_max_ms=<n> gap_after=<eot|backchannel|tool|wait> fillers=<kind+kind|none> answer_ms=<n> first_sound_ms=<n>`.
 * A clip still playing when the answer's audio is in covers the time up to it. Thread-safe.
 *
 * Every turn also gets one first-sound line ([firstSoundLine], [silentLine]; 2026-11-21, the guard
 * on the instant acknowledgement), numbers and fixed tokens only:
 * `first_sound_ms=<n|none> first_sound_after_speech_ms=<n|none> kind=<ack|tool|wait|answer|none> skip=<why|->`,
 * from the end-of-turn decision and from his last voiced frame ([speechEndAtMs], 0 when unknown).
 */
internal class TurnGap(private val eotAtMs: Long, private val speechEndAtMs: Long = 0L) {
    private class Sound(val kind: String, val startMs: Long, var endMs: Long?)

    private val sounds = mutableListOf<Sound>()
    private var logged = false
    private var firstKind: String? = null
    private var firstAtMs = 0L
    private var firstLogged = false
    private var skip: String? = null

    /** Why the acknowledgement did not play this turn, for the first-sound line. */
    @Synchronized
    fun noteSkip(token: String) {
        if (skip == null) skip = token
    }

    /** The answer's first audio: the first sound if nothing came before it. */
    @Synchronized
    fun answerHeard(atMs: Long) = noteFirst(ANSWER, atMs)

    private fun noteFirst(kind: String, atMs: Long) {
        if (firstKind != null) return
        firstKind = kind
        firstAtMs = atMs
    }

    /** The first-sound line once there is a first sound; null before it and after it was given. */
    @Synchronized
    fun firstSoundLine(): String? {
        val kind = firstKind?.takeUnless { firstLogged } ?: return null
        firstLogged = true
        val afterSpeech = if (speechEndAtMs > 0) "${firstAtMs - speechEndAtMs}" else "none"
        return "first_sound_ms=${firstAtMs - eotAtMs} first_sound_after_speech_ms=$afterSpeech " +
            "kind=${token(kind)} skip=${skip ?: "-"}"
    }

    /** The turn ended: its first-sound line if not given yet, `kind=none` when nothing was heard. */
    @Synchronized
    fun silentLine(): String? = firstSoundLine() ?: if (firstLogged) {
        null
    } else {
        firstLogged = true
        "first_sound_ms=none first_sound_after_speech_ms=none kind=none skip=${skip ?: "-"}"
    }

    private fun token(kind: String) = if (kind == BACKCHANNEL) "ack" else kind

    /** A filler clip of [kind] started at [atMs]; the returned id ends it ([ended]). */
    @Synchronized
    fun started(kind: String, atMs: Long): Int {
        noteFirst(kind, atMs)
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
        val heard = sounds.filter { it.startMs < answerAtMs }
        val fillers = heard.joinToString("+") { it.kind }.ifEmpty { "none" }
        // The first thing he hears after his turn, a filler or the answer itself (2026-11-21).
        val firstSound = (heard.minOfOrNull { it.startMs } ?: answerAtMs) - eotAtMs
        return "gap_max_ms=$maxGap gap_after=$maxAfter fillers=$fillers answer_ms=${answerAtMs - eotAtMs} " +
            "first_sound_ms=$firstSound"
    }

    companion object {
        const val EOT = "eot"
        const val BACKCHANNEL = "backchannel"
        const val TOOL = "tool"
        const val WAIT = "wait"
        const val ANSWER = "answer"
    }
}
