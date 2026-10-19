package com.kevin.legion.games.race

/** The race's duration from the engine's `start_at` (ticket 04). The engine sets `ends_at`; this is the fallback and the display bound. */
const val RACE_SECONDS = 180

/** Where a race stands, in the words the screen will say. Pure: derived from a [MatchState] and a clock. */
sealed interface RacePhase {
    /** I was invited and have not answered. */
    data class Incoming(val fromName: String) : RacePhase

    /** I invited and they have not answered. */
    data class Waiting(val opponentName: String) : RacePhase

    /** The invite is over and no race will happen; [sentence] says why, in words. */
    data class Closed(val sentence: String) : RacePhase

    data class Countdown(val secondsLeft: Int) : RacePhase

    data class Playing(val secondsLeft: Int) : RacePhase

    /** My three minutes are up; the engine has not yet called the race. */
    data object TimeUp : RacePhase

    data class Finished(val result: RaceResult?) : RacePhase
}

/**
 * The offset to add to this phone's clock to read the engine's. Measured at the moment a match
 * reply arrives: [MatchState.serverNowMs] minus the phone's own time then. Network latency lands
 * inside the offset (about half the round trip), which is far under the 1 s a countdown shows.
 */
fun clockOffsetMs(match: MatchState, localNowMs: Long): Long = match.serverNowMs - localNowMs

/**
 * The phase of [match] at engine time [serverNowMs], seen from the inviter ([asInviter]) or the
 * invitee. The closed sentences differ by side because "declined" is only ever news to the person
 * who asked.
 */
fun racePhase(match: MatchState, asInviter: Boolean, serverNowMs: Long): RacePhase {
    val them = match.opponentName
    return when (match.status) {
        MatchStatus.INVITED -> if (asInviter) RacePhase.Waiting(them) else RacePhase.Incoming(them)
        MatchStatus.DECLINED -> RacePhase.Closed("$them declined the race.")
        MatchStatus.CANCELLED ->
            RacePhase.Closed(if (asInviter) "You cancelled the invite." else "$them cancelled the invite.")
        MatchStatus.EXPIRED -> RacePhase.Closed(
            if (asInviter) "$them did not answer in time, so the invite expired." else "The invite from $them expired.",
        )
        MatchStatus.ENDED -> RacePhase.Finished(match.result)
        MatchStatus.PLAYING -> playingPhase(match, serverNowMs)
    }
}

private const val MS = 1000L

private fun playingPhase(match: MatchState, serverNowMs: Long): RacePhase {
    val start = match.startAtMs ?: return RacePhase.Waiting(match.opponentName)
    val end = match.endsAtMs ?: (start + RACE_SECONDS * MS)
    return when {
        serverNowMs < start -> RacePhase.Countdown(ceilSeconds(start - serverNowMs))
        serverNowMs < end -> RacePhase.Playing(ceilSeconds(end - serverNowMs))
        else -> RacePhase.TimeUp
    }
}

private fun ceilSeconds(ms: Long): Int = ((ms + MS - 1) / MS).toInt()

/** The result line, in words. A null result (the engine has not named one) says so rather than guessing. */
fun resultSentence(result: RaceResult?, mine: Int, theirs: Int, them: String): String = when (result) {
    RaceResult.WON -> "You won, $mine to $theirs."
    RaceResult.LOST -> "$them won, $theirs to $mine."
    RaceResult.DRAW -> "A draw, $mine each."
    null -> "The engine has not reported a result yet."
}
