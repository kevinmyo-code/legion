package com.kevin.midnightai.navigation

import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review round edd3a320, item 4: a voice call that timed out never starts anything later, and
 * start_navigation succeeds only for a preview of the same place that navigate_to planned, within
 * [NavVoice.START_WINDOW_MS]. The driver's agreement is enforced in code, not only in the tool text.
 */
class NavVoiceFlowTest {
    private class SlowSdk : NavSdk {
        override var listener: NavSdkListener? = null
        var answerRoute = true
        var answerSet = true
        var lateRoute: ((RouteResult) -> Unit)? = null
        var lateSet: ((Boolean) -> Unit)? = null
        var running = false
        override fun requestRoute(origin: GeoPoint, destination: GeoPoint, onResult: (RouteResult) -> Unit) {
            val ready = RouteResult.Ready("token", NavRoute(600.0, 8_000.0, null))
            if (answerRoute) onResult(ready) else lateRoute = { onResult(ready) }
        }
        override fun setRoute(token: Any, onDone: (Boolean) -> Unit) {
            if (answerSet) onDone(true) else lateSet = onDone
        }
        override fun startSession() { running = true }
        override fun stopAndClear() { running = false }
        override fun isSessionRunning() = running
        override fun destroy() = Unit
    }

    private val sdk = SlowSdk()
    private var now = 1_000_000L
    private val home = NavVoice.Place("home", GeoPoint(29.76, -95.37))
    private val controller = NavController(
        sdkFactory = { sdk },
        hasToken = { true },
        availability = { null },
        position = { GeoPoint(29.70, -95.40) },
        onTripStarted = {},
        onTripEnded = {},
        schedule = { _, _ -> },
    )

    @Before fun fastAndLocal() {
        NavVoice.mainContext = EmptyCoroutineContext
        NavVoice.timeoutMs = 50L
        NavVoice.nowMs = { now }
        NavVoice.forgetPlanForTest()
    }

    @After fun restore() = NavVoice.resetForTest()

    private fun ready(result: RouteResult) = result

    @Test fun aRouteRequestThatTimedOutIsCancelledSoItsLateAnswerShowsNothing() = runBlocking {
        sdk.answerRoute = false
        val r = NavVoice.navigateTo(controller, home)
        assertFalse(r.ok)
        sdk.lateRoute!!.invoke(ready(RouteResult.Ready("token", NavRoute(1.0, 1.0, null))))
        assertNotEquals("a late route appeared after 'nothing was started'", NavPhase.PREVIEW, controller.state.value.phase)
    }

    @Test fun aGoThatTimedOutNeverStartsGuidanceLater() = runBlocking {
        assertTrue(NavVoice.navigateTo(controller, home).ok)
        sdk.answerSet = false
        assertFalse(NavVoice.start(controller).ok)
        sdk.lateSet!!.invoke(true)
        assertNotEquals("guidance started after the driver was told nothing started", NavPhase.GUIDING, controller.state.value.phase)
        assertFalse(sdk.running)
    }

    @Test fun startNeedsAPreviewThatNavigateToPlannedForThatPlaceRecently() = runBlocking {
        // A preview started by a tap, not by navigate_to: the voice tool may not start it.
        controller.navigateTo(NavDestination("home", home.point))
        assertFalse("started a preview the voice tool never planned", NavVoice.start(controller).ok)
        controller.cancel()

        assertTrue(NavVoice.navigateTo(controller, home).ok)
        now += NavVoice.START_WINDOW_MS + 1
        assertFalse("started a stale plan", NavVoice.start(controller).ok)

        controller.cancel()
        assertTrue(NavVoice.navigateTo(controller, home).ok)
        now += 5_000
        val started = NavVoice.start(controller)
        assertTrue(started.message, started.ok)
        assertEquals(NavPhase.GUIDING, controller.state.value.phase)
    }
}
