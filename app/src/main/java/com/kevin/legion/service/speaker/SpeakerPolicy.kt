package com.kevin.legion.service.speaker

import java.util.Locale

/**
 * "Only my voice" (Kevin 2026-11-16: "can't everything key to my voice only. Including wake word?"):
 * every number and rule of the speaker check in one place. Pure, so the whole policy is a JVM test.
 *
 * The numbers come from the laptop study (`C:\Users\kevin\.legion\reports\speaker-recognition-study-1116.md`,
 * kept off the repo because it is about his voice): ERes2Net-en, the 1 s window ending at the wake
 * hit, gain-normalised. The thresholds were picked looking at the same data the study measured, so
 * they are optimistic: shadow mode exists to set them from real wakes.
 */
object SpeakerPolicy {
    /** A score at or above this opens the conversation as today. */
    const val OPEN_AT = 0.45f

    /** A score below this is ignored silently. Between the two the phone asks for a tap. */
    const val IGNORE_BELOW = 0.30f

    /**
     * How long a gated wake waits for its score before it opens anyway. The study measured 31 ms per
     * check on the laptop CPU; the phone is unmeasured (a 2-4x guess is 60-130 ms). 300 ms keeps a
     * slow phone inside the check and stays well under the capture's 800 ms record buffer, which
     * the waiting capture loop must not overrun.
     */
    const val CHECK_DEADLINE_MS = 300L

    /** The scored window: 1 s, as studied. */
    const val WINDOW_MS = 1_000

    /**
     * Where that 1 s is looked for: the loudest 1 s in the 1.6 s ending at the hit. The study
     * searched [hit - 1.6 s, hit + 0.2 s]; the phone cannot see past the hit, so both the live check
     * and enrolment end the search at the hit, the same way.
     */
    const val SEARCH_MS = 1_600

    /** Fewest confirmed takes a voiceprint is built from (the study enrolled on 19-20). */
    const val MIN_ENROL_TAKES = 8

    /** How long the "was that you?" tap stays up before it goes away by itself. */
    const val ASK_TIMEOUT_MS = 8_000L

    /**
     * The logcat tag every speaker line goes under. Local only: a per-wake match score is derived
     * from his voice, so it is never uploaded. It is not a [com.kevin.legion.service.LiveLatency]
     * tag (those reach the engine spool), and the phone's logcat capture drops it before its spool
     * ([com.kevin.legion.service.phonelog.LogcatLines.LOCAL_ONLY_TAGS]). `adb logcat -s SPEAKER_LOCAL`.
     */
    const val LOG_TAG = "SPEAKER_LOCAL"

    /** What a wake hit does. */
    enum class Band(val word: String) { OPEN("open"), ASK("ask"), IGNORE("ignore") }

    /** Switch off: score and log, gate nothing. Switch on: gate. */
    enum class Mode(val word: String) { SHADOW("shadow"), GATED("gated") }

    fun mode(onlyMyVoice: Boolean): Mode = if (onlyMyVoice) Mode.GATED else Mode.SHADOW

    fun band(score: Float): Band = when {
        score.isNaN() -> Band.OPEN
        score >= OPEN_AT -> Band.OPEN
        score < IGNORE_BELOW -> Band.IGNORE
        else -> Band.ASK
    }

    /**
     * What the wake actually does. Shadow never holds a wake back. Gated holds one back only on a
     * real score; every failure (no voiceprint, no model, a throw, the deadline) opens as today.
     */
    fun act(mode: Mode, result: SpeakerResult): Band = when {
        mode == Mode.SHADOW -> Band.OPEN
        result is SpeakerResult.Scored -> band(result.score)
        else -> Band.OPEN
    }

    /**
     * One [LOG_TAG] line, logcat only. Scores and timings only, never audio or the voiceprint. Only a
     * scored check carries a `_ms` key, so a timing summary over the lines counts real checks only.
     */
    fun line(mode: Mode, result: SpeakerResult, acted: Band): String = when (result) {
        is SpeakerResult.Scored ->
            "speaker_check_ms=${result.ms} mode=${mode.word} result=scored score=" +
                "%.3f".format(Locale.ROOT, result.score) + " would=${band(result.score).word} acted=${acted.word}"
        is SpeakerResult.TimedOut ->
            "speaker_check mode=${mode.word} result=timeout waited=${result.waitedMs} acted=${acted.word}"
        else -> "speaker_check mode=${mode.word} result=${result.word} acted=${acted.word}"
    }
}

/** How one check ended. [word] is what the log says. */
sealed class SpeakerResult(val word: String) {
    data class Scored(val score: Float, val ms: Long) : SpeakerResult("scored")
    data object NoVoiceprint : SpeakerResult("no_voiceprint")
    data object ModelMissing : SpeakerResult("model_missing")
    data object ModelLoading : SpeakerResult("model_loading")
    data object NoAudio : SpeakerResult("no_audio")

    /** An earlier check that ran past its deadline is still computing. */
    data object Busy : SpeakerResult("busy")
    data class TimedOut(val waitedMs: Long) : SpeakerResult("timeout")

    /** [reason] is an exception class name, never a message that could carry data. */
    data class Failed(val reason: String) : SpeakerResult("failed_$reason")
}
