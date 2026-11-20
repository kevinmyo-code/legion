package com.kevin.legion.service.turn

import com.kevin.legion.service.turn.EndOfTurnPolicy.Action.EndActivity
import com.kevin.legion.service.turn.EndOfTurnPolicy.Reason
import com.kevin.legion.service.turn.EndOfTurnPolicy.Score
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-of-turn LIVE_LATENCY line both voice paths write (2026-11-20): the Smart Turn scores of the
 * last checks, so drive logs can show how often a score lands near the 0.5 threshold. Parsed by
 * `tools/voice_latency.py` and `server/devicelogs/voice_audit.py` as key=value pairs.
 */
class EndOfTurnLineTest {

    @Test
    fun `the line carries the scores of the last checks, when asked and when back`() {
        val end = EndActivity(
            Reason.EXTENDED,
            silenceMs = 1_100,
            scores = listOf(Score(0.12f, 192, 480), Score(0.71f, 480, 1_100)),
            longestSilenceMs = 1_100,
        )
        assertEquals(
            "eot_ms=1100 eot_reason=extended eot_longest_silence_ms=1100 st_checks=2 " +
                "st_scores=0.12@192>480,0.71@480>1100",
            EndOfTurnLine.of(end),
        )
    }

    @Test
    fun `no checks says none, and only the last four checks are listed`() {
        val none = EndOfTurnLine.of(EndActivity(Reason.BASE, silenceMs = 704, scores = emptyList()))
        assertTrue(none, none.endsWith("st_checks=0 st_scores=none"))
        val many = (1..6).map { Score(it / 10f, it * 100, it * 100 + 50) }
        val line = EndOfTurnLine.of(EndActivity(Reason.CAP, silenceMs = 2_500, scores = many))
        assertTrue(line, line.endsWith("st_checks=6 st_scores=0.30@300>350,0.40@400>450,0.50@500>550,0.60@600>650"))
    }

    @Test
    fun `the line is keys and numbers only`() {
        val end = EndActivity(Reason.EXTENDED, silenceMs = 900, scores = listOf(Score(0.4567f, 192, 700)))
        val pairs = EndOfTurnLine.of(end).split(" ")
        val number = Regex("""^-?\d+(\.\d+)?$""")
        val score = Regex("""^\d\.\d\d@\d+>\d+$""")
        for (pair in pairs) {
            val (key, value) = pair.split("=", limit = 2)
            assertTrue(pair, Regex("""^[a-z_]+$""").matches(key))
            val ok = number.matches(value) || value.split(",").all { score.matches(it) } ||
                (key == "eot_reason" && value in Reason.entries.map { it.wire })
            assertTrue(pair, ok)
        }
    }
}
