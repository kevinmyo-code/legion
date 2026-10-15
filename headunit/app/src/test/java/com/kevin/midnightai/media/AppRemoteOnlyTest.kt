package com.kevin.midnightai.media

import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-10-15 App-Remote-only ruling and the audit fixes that pure logic can prove. What these
 * cannot prove (the SDK no longer throwing off the main thread, Midnight really coming back to the
 * front, Spotify's CallResult timing) needs the head unit and is listed in the report.
 */
class AppRemoteOnlyTest {

    private val restoreOnMain = MainThreadHop.onMainThread
    private val restorePost = MainThreadHop.postToMain

    @After fun restore() {
        MainThreadHop.onMainThread = restoreOnMain
        MainThreadHop.postToMain = restorePost
    }

    // --- F1: the SDK connect is handed to the main looper ---------------------------------------

    @Test fun `off the main thread the connect is posted, not run`() {
        var ran = false
        val posted = mutableListOf<Runnable>()
        MainThreadHop.run(onMain = false, post = { posted += it }, block = Runnable { ran = true })
        assertFalse("must not run inline on a worker thread", ran)
        assertEquals(1, posted.size)
        posted.single().run()
        assertTrue(ran)
    }

    @Test fun `on the main thread the connect runs inline`() {
        var ran = false
        var posted = false
        MainThreadHop.run(onMain = true, post = { posted = true }, block = Runnable { ran = true })
        assertTrue(ran)
        assertFalse(posted)
    }

    @Test fun `onMain consults the seams so a JVM test never needs a Looper`() {
        val posted = mutableListOf<Runnable>()
        MainThreadHop.onMainThread = { false }
        MainThreadHop.postToMain = { posted += it }
        var ran = false
        MainThreadHop.onMain(Runnable { ran = true })
        assertFalse(ran)
        assertEquals(1, posted.size)
    }

    // --- F6: a recent failure fails fast ---------------------------------------------------------

    @Test fun `inside the recent failure window no attempt is spent`() {
        assertFalse(SpotifyConnectLogic.shouldTryAppRemote(false, false, 1_000))
        assertTrue(SpotifyConnectLogic.shouldTryAppRemote(false, true, 1_000))
    }

    // --- F9: a connect does not take the speakers from a playing phone --------------------------

    @Test fun `a connect claims the source only when the phone is the source and is not playing`() {
        assertTrue(SpotifyController.shouldClaimSourceOnConnect(Source.PHONE, phonePlaying = false))
        assertFalse(SpotifyController.shouldClaimSourceOnConnect(Source.PHONE, phonePlaying = true))
        assertFalse(SpotifyController.shouldClaimSourceOnConnect(Source.MIXTAPE, phonePlaying = false))
        assertFalse(SpotifyController.shouldClaimSourceOnConnect(Source.SPOTIFY, phonePlaying = false))
    }

    // --- Return to Midnight -----------------------------------------------------------------------

    @Test fun `Midnight is brought back only if it was in front and is not now`() {
        assertTrue(SpotifyController.shouldReturnToFront(wasInFront = true, nowInFront = false))
        assertFalse(SpotifyController.shouldReturnToFront(wasInFront = true, nowInFront = true))
        // Behind another app before the play: a voice play does not drag the launcher forward.
        assertFalse(SpotifyController.shouldReturnToFront(wasInFront = false, nowInFront = false))
    }

    // --- Gate wording: every miss says what did not happen ----------------------------------------

    @Test fun `every gate that did not connect names the action that did not happen`() {
        val gates = listOf(
            SpotifyController.Gate.NotSetUp,
            SpotifyController.Gate.Unreadable,
            SpotifyController.Gate.TimedOut,
            SpotifyController.Gate.Failed(CouldNotFindSpotifyApp()),
            SpotifyController.Gate.Failed(null),
        )
        gates.filter { it != SpotifyController.Gate.Unreadable }.forEach {
            assertTrue(SpotifyController.gateMessage(it, "pause").contains("pause"))
        }
        assertEquals(gates.size, gates.map { SpotifyController.gateMessage(it, "pause") }.toSet().size)
    }

    @Test fun `only a failed or timed-out attempt claims a reconnect, and unreadable says re-enter`() {
        assertTrue(SpotifyController.gateMessage(SpotifyController.Gate.Failed(null), "skip").contains("reconnect"))
        assertTrue(SpotifyController.gateMessage(SpotifyController.Gate.TimedOut, "skip").contains("retrying"))
        assertFalse(SpotifyController.gateMessage(SpotifyController.Gate.NotSetUp, "skip").contains("reconnect"))
        val unreadable = SpotifyController.gateMessage(SpotifyController.Gate.Unreadable, "skip")
        assertTrue(unreadable.contains("can't read the saved Spotify setup"))
        assertFalse(unreadable.contains("reconnect"))
    }

    @Test fun `outcomeForGate keeps unreadable and not-set-up apart`() {
        assertEquals(SpotifyController.PlayOutcome.NotSetUp, SpotifyController.outcomeForGate(SpotifyController.Gate.NotSetUp))
        assertEquals(
            SpotifyController.PlayOutcome.ClientIdUnreadable,
            SpotifyController.outcomeForGate(SpotifyController.Gate.Unreadable),
        )
        assertEquals(SpotifyController.PlayOutcome.StillConnecting, SpotifyController.outcomeForGate(SpotifyController.Gate.TimedOut))
        assertEquals(
            SpotifyController.PlayOutcome.NotInstalled,
            SpotifyController.outcomeForGate(SpotifyController.Gate.Failed(CouldNotFindSpotifyApp())),
        )
        listOf(
            SpotifyController.PlayOutcome.NotSetUp,
            SpotifyController.PlayOutcome.ClientIdUnreadable,
            SpotifyController.PlayOutcome.StillConnecting,
        ).forEach {
            assertFalse(SpotifyController.succeeded(it))
            assertTrue(SpotifyController.message(it, "Chill").isNotBlank())
        }
    }

    // --- F4: transport sentences ------------------------------------------------------------------

    @Test fun `a landed transport has no failure sentence and a rejected one does not claim success`() {
        assertNull(SpotifyController.transportMessage(SpotifyController.TransportOutcome.Done, SpotifyController.TransportCmd.PAUSE))
        val rejected = SpotifyController.transportMessage(
            SpotifyController.TransportOutcome.Rejected, SpotifyController.TransportCmd.NEXT,
        )
        assertNotNull(rejected)
        assertTrue(rejected!!.contains("didn't confirm"))
        val down = SpotifyController.transportMessage(
            SpotifyController.TransportOutcome.Unavailable(SpotifyController.Gate.Failed(null)),
            SpotifyController.TransportCmd.PLAY,
        )
        assertTrue(down!!.contains("isn't connected"))
    }

    // --- F12: the phone failure names the phone ---------------------------------------------------

    @Test fun `a phone transport failure blames the phone, not Spotify`() {
        val m = MusicRouter.phoneFailureMessage(SpotifyController.TransportCmd.PLAY)
        assertTrue(m.contains("phone"))
        assertFalse(m.contains("Spotify"))
    }

    // --- Em dashes -----------------------------------------------------------------------------------

    @Test fun `none of the new spoken lines carries an em dash`() {
        val lines = listOf(
            SpotifyController.notConnectedMessage(),
            SpotifyController.clientIdUnreadableMessage(),
            SpotifyController.playStillPendingMessage("x"),
            SpotifyController.gateMessage(SpotifyController.Gate.Failed(null), "pause"),
            MusicRouter.phoneFailureMessage(SpotifyController.TransportCmd.NEXT),
        )
        lines.forEach { assertFalse(it, it.contains('—')) }
    }
}
