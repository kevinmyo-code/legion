package com.kevin.midnightai.media

import com.kevin.midnightai.media.LatePlayReportLogic.Late
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatePlayReportLogicTest {

    @Test fun `a late success names what Spotify resolved`() {
        assertEquals(
            "\"Mask Off, Future\" is playing now on Spotify.",
            LatePlayReportLogic.spokenLine(Late.Started("Mask Off, Future")),
        )
    }

    @Test fun `a late success with no resolved label does not echo the request`() {
        assertEquals("Spotify is playing now.", LatePlayReportLogic.spokenLine(Late.Started(null)))
        assertEquals("Spotify is playing now.", LatePlayReportLogic.spokenLine(Late.Started("  ")))
    }

    @Test fun `a late failure speaks the plain reason`() {
        assertEquals("It did not start.", LatePlayReportLogic.spokenLine(Late.Failed("It did not start.")))
    }

    @Test fun `a failure with no reason says nothing rather than something invented`() {
        assertNull(LatePlayReportLogic.spokenLine(Late.Failed("")))
    }

    @Test fun `a superseded play says nothing`() {
        assertNull(LatePlayReportLogic.spokenLine(Late.Superseded))
    }

    @Test fun `only a Started outcome is classified as a success`() {
        val started = SpotifyController.PlayOutcome.Started(pickedLabel = "Mask Off, Future", viaWebApi = true)
        assertEquals(Late.Started("Mask Off, Future"), LatePlayReportLogic.classify(started, "Playing it."))
        assertEquals(Late.Superseded, LatePlayReportLogic.classify(SpotifyController.PlayOutcome.Superseded, "x"))
        assertEquals(
            Late.Failed("Spotify wouldn't start it."),
            LatePlayReportLogic.classify(SpotifyController.PlayOutcome.PlayRejected, "Spotify wouldn't start it."),
        )
    }

    @Test fun `a play that never reached Spotify is a failure carrying its sentence`() {
        assertEquals(
            Late.Failed("I couldn't find it."),
            LatePlayReportLogic.classify(null, "I couldn't find it."),
        )
    }

    @Test fun `every web failure outcome is a failure, never a success`() {
        val web = SpotifyController.PlayOutcome.WebFailed(SpotifyConnectLogic.Failure.Unreachable)
        assertTrue(LatePlayReportLogic.classify(web, "Cannot reach.") is Late.Failed)
    }

    @Test fun `the prompt carries the line, forbids guessing and has no em dash`() {
        val p = LatePlayReportLogic.prompt("It did not start.")
        assertTrue(p.contains("Result: It did not start."))
        assertTrue(p.contains("do not guess"))
        assertFalse(p.contains('—'))
    }
}
