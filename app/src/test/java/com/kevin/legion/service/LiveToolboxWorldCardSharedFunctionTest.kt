package com.kevin.legion.service

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Command-center ticket 08's drift-debt half, proved rather than asserted by doc comment: the
 * hands paths this ticket lifted out of `private` LiveToolbox functions must reach the exact SAME
 * function `dispatch` reaches, not two call sites that happen to agree today.
 * [LiveToolbox.resolveCurrentLocation] (the `ui/FleetScreen.kt` restatement `getCurrentLocation`
 * used to have) and [LiveToolbox.controlMusicTransport] (the `ui/media/MediaTransport.kt`
 * restatement `control_music` used to have) are both pure local reads/writes - no network, no
 * Google account - so they are safe to call directly under Robolectric.
 *
 * **`track_package`/`flight_status` are deliberately NOT covered here.** Both route through
 * [com.kevin.legion.gmail.GmailAuth.authorize], which calls
 * `Identity.getAuthorizationClient(context).authorize(request())` - a real Google Identity API
 * client, not a stub. Under Robolectric (no live Play Services connection) that call does not
 * throw or fail fast the way this file originally assumed; it hangs waiting on a callback that
 * never arrives, which stalled `testDebugUnitTest` for the whole suite when first tried here
 * (observed directly: multiple minutes with no test-result XML written, traced to this exact call
 * by grepping for the only two callers of `GmailAuth` under `app/src/test/`, of which this file
 * was one before the rewrite). No other test in this repo calls `GmailAuth.authorize`/
 * `tokenOrReason` under Robolectric either - see [com.kevin.legion.gmail.GmailToolLogicTest]'s own
 * doc comment ("No Android, no GmailAuth, no GmailClient"). The "same shared function" claim for
 * [LiveToolbox.trackPackage]/[LiveToolbox.flightStatus] was `ui/world/PackageFlightCards.kt` calling
 * (the cards were later retired; the internal widening stays for the voice path and any future hands one)
 * the identical `internal` function `dispatch` calls (traced by reading both call sites, same
 * posture [com.kevin.legion.ui.body.BodyWriteSameFunctionTest]'s own doc comment takes for a claim
 * this repo has no safe harness to execute), not proved by a running test.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxWorldCardSharedFunctionTest {

    private val context = RuntimeEnvironment.getApplication()

    // ------------------------------------------------------------------ current-location readout

    @Test
    fun `resolveCurrentLocation is one function both getCurrentLocation and FleetScreen read`() = runBlocking {
        // No GPS fix ever exists under Robolectric with no LocationController.init side effects
        // wired to a real provider, so this always lands on one of the three failure branches -
        // deterministic either way, and it is the BRANCH VALUE under test, not which one it lands on.
        val readout = LiveToolbox.resolveCurrentLocation(context)
        val getCurrentLocationResult = LiveToolbox.dispatch(context, "get_current_location", JSONObject())
        assertTrue(getCurrentLocationResult != null)
        when (readout) {
            LiveToolbox.LocationReadout.NoPermission, LiveToolbox.LocationReadout.ProvidersOff, LiveToolbox.LocationReadout.NoFix ->
                assertTrue(
                    "get_current_location must fail exactly when resolveCurrentLocation says there's no fix",
                    !getCurrentLocationResult!!.optBoolean("success"),
                )
            is LiveToolbox.LocationReadout.Available ->
                assertTrue(getCurrentLocationResult!!.optBoolean("success"))
        }
    }

    // ------------------------------------------------------------------ control_music transport

    @Test
    fun `controlMusicTransport direct call and control_music PLAY dispatch reach the identical function`() = runBlocking {
        val direct = LiveToolbox.controlMusicTransport(context, LiveToolbox.MusicAction.PLAY)
        val dispatched = LiveToolbox.dispatch(
            context, "control_music",
            JSONObject().put("action", LiveToolbox.MusicAction.PLAY.wireValue),
        )
        assertJsonEquals(direct, dispatched!!)
    }

    private fun assertJsonEquals(a: JSONObject, b: JSONObject) {
        assertEquals(a.toString(), b.toString())
    }
}
