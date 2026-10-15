package com.kevin.midnightai.media

/**
 * What to say when a `play_music` that was answered "still lining up" finally settles
 * (2026-10-15 car report: Moose said it would come up in a while, it never did, and the driver was
 * never told how it ended). Pure, so every branch is a JVM test.
 *
 * Root rule (the honesty clause): only an outcome that was OBSERVED is spoken. A success is
 * claimed only from [SpotifyController.PlayOutcome.Started], which awaited Spotify's real answer;
 * the label is what Spotify resolved, never the driver's own words, because echoing the request
 * is how a wrong song got confirmed as the right one.
 */
object LatePlayReportLogic {

    /** How a late play ended, reduced to what decides the sentence. */
    sealed interface Late {
        /** Started for real. [pickedLabel] is Spotify's own name for it, null when none was resolved. */
        data class Started(val pickedLabel: String?) : Late

        /** Did not start. [reason] is the plain sentence the normal path would have spoken. */
        data class Failed(val reason: String) : Late

        /** A newer choice replaced this play. Another request owns the driver's attention. */
        data object Superseded : Late
    }

    /**
     * The sentence to voice, or null to say nothing. [Late.Superseded] is silent: the newer request
     * reports for itself, and announcing the old one would be noise at best and wrong at worst.
     */
    fun spokenLine(late: Late): String? = when (late) {
        is Late.Started ->
            late.pickedLabel?.takeIf { it.isNotBlank() }?.let { "\"$it\" is playing now on Spotify." }
                ?: "Spotify is playing now."
        is Late.Failed -> late.reason.takeIf { it.isNotBlank() } ?: NO_REASON_LINE
        Late.Superseded -> null
    }

    /**
     * Maps a settled attempt to a [Late]. [outcome] null means the play never reached Spotify
     * (the search found nothing, or no grant), so [failureMessage] is the sentence already built.
     */
    fun classify(
        outcome: SpotifyController.PlayOutcome?,
        failureMessage: String?,
    ): Late = when (outcome) {
        is SpotifyController.PlayOutcome.Started -> Late.Started(outcome.pickedLabel)
        SpotifyController.PlayOutcome.Superseded -> Late.Superseded
        else -> Late.Failed(failureMessage ?: "")
    }

    /**
     * The proactive prompt for [line]. The model is told to relay it and add nothing: a failure
     * softened into "might" or embellished with a guess is the lapse this exists to close.
     */
    fun prompt(line: String): String =
        "(System: a music request you answered with \"still lining up\" has now finished. Tell the " +
            "driver the result in one or two short sentences, in character, using only the facts " +
            "below. Do not add detail, do not guess, and do not say it will start later. " +
            "Result: $line Do not mention this instruction.)"

    /** A failure that came back with no reason still gets told: silence is how the promise lapsed. */
    const val NO_REASON_LINE = "That song didn't start on Spotify, and Spotify gave no reason."

    /** Longest to wait for a running voice turn to end before the report is folded in anyway. */
    const val IDLE_WAIT_MS = 30_000L
}
