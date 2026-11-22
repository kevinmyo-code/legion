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
 * `first_sound_ms=<n|none> handed_ms=<n|none> first_sound_after_speech_ms=<n|none> kind=<..> skip=<why|->`:
 * first_sound_ms when the first sound was heard (the playback head moved, [heard]), handed_ms when it
 * was handed to the player, from the end-of-turn decision; after_speech from his last voiced frame.
 */
internal class TurnGap(private val eotAtMs: Long, private val speechEndAtMs: Long = 0L) {
    private class Sound(val kind: String, val startMs: Long, var endMs: Long?)

    private val sounds = mutableListOf<Sound>()
    private var logged = false
    private var firstKind: String? = null
    private var firstAtMs = 0L
    private var heardAtMs: Long? = null
    private var firstLogged = false
    private var skip: String? = null

    /** Why the acknowledgement did not play this turn, for the first-sound line. */
    @Synchronized
    fun noteSkip(token: String) {
        if (skip == null) skip = token
    }

    /** The answer's first audio: the first sound if nothing came before it. */
    @Synchronized
    fun answerHeard(atMs: Long) {
        if (firstKind != null) return
        firstKind = ANSWER
        firstAtMs = atMs
    }

    /** The first sound was HEARD at [atMs]: the playback head moved past its first frames. Once. */
    @Synchronized
    fun heard(atMs: Long) {
        if (heardAtMs == null && firstKind != null) heardAtMs = atMs
    }

    /** True once a sound was handed over and not yet heard: worth watching the playback head for. */
    @Synchronized
    fun awaitingHeard(): Boolean = firstKind != null && heardAtMs == null && !firstLogged

    /**
     * The first-sound line once the first sound was heard; null before, and after it was given.
     * first_sound_ms is when it was heard (2026-11-21: the hand-off time said 15 ms for an ack heard
     * seconds later or never); handed_ms is when it was handed to the player.
     */
    @Synchronized
    fun firstSoundLine(): String? {
        val heard = heardAtMs?.takeUnless { firstLogged } ?: return null
        firstLogged = true
        return soundLine(heard)
    }

    /** The turn ended: its line if not given yet, with first_sound_ms=none when nothing was heard. */
    @Synchronized
    fun silentLine(): String? = firstSoundLine() ?: if (firstLogged) {
        null
    } else {
        firstLogged = true
        soundLine(null)
    }

    private fun soundLine(heard: Long?): String {
        val kind = firstKind?.let { if (it == BACKCHANNEL) "ack" else it } ?: "none"
        val handed = firstKind?.let { "${firstAtMs - eotAtMs}" } ?: "none"
        val afterSpeech = if (heard != null && speechEndAtMs > 0) "${heard - speechEndAtMs}" else "none"
        return "first_sound_ms=${heard?.let { "${it - eotAtMs}" } ?: "none"} handed_ms=$handed " +
            "first_sound_after_speech_ms=$afterSpeech kind=$kind skip=${skip ?: "-"}"
    }

    /** A filler clip of [kind] started at [atMs]; the returned id ends it ([ended]). */
    @Synchronized
    fun started(kind: String, atMs: Long): Int {
        if (firstKind == null) {
            firstKind = kind
            firstAtMs = atMs
        }
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
