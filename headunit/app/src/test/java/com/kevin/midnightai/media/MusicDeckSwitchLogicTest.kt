package com.kevin.midnightai.media

import com.kevin.midnightai.media.MusicDeckLogic.SwitchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PHONE | SPOTIFY switch when App Remote drops (2026-10-15 car report). */
class MusicDeckSwitchLogicTest {

    @Test fun `connected Spotify shows the live switch`() {
        assertEquals(SwitchMode.LIVE, MusicDeckLogic.switchMode(true, true, Source.PHONE))
        assertEquals(SwitchMode.LIVE, MusicDeckLogic.switchMode(true, false, Source.SPOTIFY))
    }

    @Test fun `a dropped link on a set-up Spotify keeps the switch as RECONNECT`() {
        assertEquals(SwitchMode.RECONNECT, MusicDeckLogic.switchMode(false, true, Source.PHONE))
        assertEquals(SwitchMode.RECONNECT, MusicDeckLogic.switchMode(false, true, Source.SPOTIFY))
    }

    @Test fun `Spotify that was never set up shows no switch`() {
        assertEquals(SwitchMode.HIDDEN, MusicDeckLogic.switchMode(false, false, Source.PHONE))
    }

    @Test fun `a loaded mixtape hides the switch in every state`() {
        assertEquals(SwitchMode.HIDDEN, MusicDeckLogic.switchMode(true, true, Source.MIXTAPE))
        assertEquals(SwitchMode.HIDDEN, MusicDeckLogic.switchMode(false, true, Source.MIXTAPE))
    }

    @Test fun `the Spotify side names what a tap does while the link is down`() {
        assertEquals("SPOTIFY", MusicDeckLogic.spotifySwitchLabel(SwitchMode.LIVE, connecting = false))
        assertEquals("RECONNECT", MusicDeckLogic.spotifySwitchLabel(SwitchMode.RECONNECT, connecting = false))
        assertEquals("CONNECTING", MusicDeckLogic.spotifySwitchLabel(SwitchMode.RECONNECT, connecting = true))
        // A live link never claims to be connecting.
        assertEquals("SPOTIFY", MusicDeckLogic.spotifySwitchLabel(SwitchMode.LIVE, connecting = true))
    }

    @Test fun `a reconnect that ran out of time does not blame a stale failure`() {
        val timedOut = MusicDeckLogic.reconnectFailureMessage("Spotify app signed out", timedOut = true)
        assertFalse(timedOut.contains("signed out"))
        assertTrue(timedOut.contains("still connecting"))
    }

    @Test fun `a reconnect that failed names the reason and says nothing switched`() {
        val msg = MusicDeckLogic.reconnectFailureMessage("Spotify app signed out", timedOut = false)
        assertTrue(msg.contains("Spotify app signed out"))
        assertTrue(msg.contains("current source"))
        assertFalse(msg.contains('—'))
    }

    @Test fun `the silent reconnect schedule retries more than once and backs off`() {
        val s = SpotifyConnectLogic.RECONNECT_SCHEDULE_MS
        assertTrue(s.size >= 2)
        assertEquals(s.sorted(), s)
        assertEquals(5_000L, s.first())
    }
}
