package com.kevin.legion.service.turn

import java.util.Locale

/**
 * The end-of-turn LIVE_LATENCY line, one format for every voice path (2026-11-20: the cascade logged
 * only how many Smart Turn checks ran, so drive logs could not show how often a score lands near
 * [EndOfTurnPolicy.THRESHOLD]). Keys and numbers only. `tools/voice_latency.py` and the engine's
 * `devicelogs/voice_audit.py` read it as key=value pairs.
 */
internal object EndOfTurnLine {
    /** Only the last few checks: a long hold runs a dozen and logcat truncates a long line. */
    const val SCORES_LOGGED = 4

    /** `eot_ms=1100 eot_reason=extended eot_longest_silence_ms=1100 st_checks=2 st_scores=0.12@192>480,...` */
    fun of(end: EndOfTurnPolicy.Action.EndActivity): String {
        val scores = end.scores.takeLast(SCORES_LOGGED).joinToString(",") {
            "%.2f@%d>%d".format(Locale.US, it.score, it.askedAtMs, it.arrivedAtMs)
        }
        // The bench greps `eot_early=1`; the score is the verdict that ended the turn (the last one).
        val early = end.scores.lastOrNull()?.takeIf { end.reason == EndOfTurnPolicy.Reason.EARLY }
            ?.let { " eot_early=1 score=%.2f".format(Locale.US, it.score) }.orEmpty()
        return "eot_ms=${end.silenceMs} eot_reason=${end.reason.wire} " +
            "eot_longest_silence_ms=${end.longestSilenceMs} st_checks=${end.scores.size} " +
            "st_scores=${scores.ifEmpty { "none" }}$early"
    }
}
