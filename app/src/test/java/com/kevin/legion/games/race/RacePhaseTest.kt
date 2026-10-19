package com.kevin.legion.games.race

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RacePhaseTest {

    @Test
    fun invitedIsWaitingForTheInviterAndIncomingForTheInvitee() {
        val m = matchState(MatchStatus.INVITED)
        assertEquals(RacePhase.Waiting("Mia"), racePhase(m, asInviter = true, serverNowMs = 0))
        assertEquals(RacePhase.Incoming("Mia"), racePhase(m, asInviter = false, serverNowMs = 0))
    }

    @Test
    fun declinedCancelledAndExpiredEachSayWhyInWords() {
        val declined = racePhase(matchState(MatchStatus.DECLINED), true, 0) as RacePhase.Closed
        assertTrue(declined.sentence, declined.sentence.contains("declined"))
        val cancelledByThem = racePhase(matchState(MatchStatus.CANCELLED), false, 0) as RacePhase.Closed
        assertTrue(cancelledByThem.sentence.contains("Mia cancelled"))
        val cancelledByMe = racePhase(matchState(MatchStatus.CANCELLED), true, 0) as RacePhase.Closed
        assertTrue(cancelledByMe.sentence.contains("You cancelled"))
        val expired = racePhase(matchState(MatchStatus.EXPIRED), true, 0) as RacePhase.Closed
        assertTrue(expired.sentence.contains("expired"))
    }

    @Test
    fun countdownRoundsUpAndThenPlayingAndTimeUp() {
        val m = matchState(MatchStatus.PLAYING, startAtMs = 10_000, endsAtMs = 190_000)
        assertEquals(RacePhase.Countdown(5), racePhase(m, true, 5_000))
        assertEquals(RacePhase.Countdown(1), racePhase(m, true, 9_001))
        assertEquals(RacePhase.Playing(180), racePhase(m, true, 10_000))
        assertEquals(RacePhase.Playing(1), racePhase(m, true, 189_500))
        assertEquals(RacePhase.TimeUp, racePhase(m, true, 190_000))
    }

    @Test
    fun endedCarriesTheEnginesResult() {
        val m = matchState(MatchStatus.ENDED, result = RaceResult.WON)
        assertEquals(RacePhase.Finished(RaceResult.WON), racePhase(m, true, 0))
    }

    @Test
    fun clockOffsetCorrectsAPhoneThatIsAheadOrBehind() {
        // The engine says it is 100_000 while the phone reads 103_000 (3 s fast): offset -3_000.
        val m = matchState(MatchStatus.PLAYING, startAtMs = 105_000, endsAtMs = 285_000, serverNowMs = 100_000)
        val offset = clockOffsetMs(m, localNowMs = 103_000)
        assertEquals(-3_000L, offset)
        // A second later the phone reads 104_000, i.e. engine time 101_000: four seconds to the start.
        assertEquals(RacePhase.Countdown(4), racePhase(m, true, 104_000 + offset))
    }

    @Test
    fun resultSentencesNameTheScoresAndNeverGuess() {
        assertEquals("You won, 900 to 400.", resultSentence(RaceResult.WON, 900, 400, "Mia"))
        assertEquals("Mia won, 900 to 400.", resultSentence(RaceResult.LOST, 400, 900, "Mia"))
        assertEquals("A draw, 500 each.", resultSentence(RaceResult.DRAW, 500, 500, "Mia"))
        assertTrue(resultSentence(null, 1, 2, "Mia").contains("not reported"))
    }
}
