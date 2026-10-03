package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.navigation.AvoidKind
import com.kevin.legion.navigation.GuidanceSnapshot
import com.kevin.legion.navigation.MapboxTokenState
import com.kevin.legion.navigation.NavDestination
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.navigation.NavRouteInfo
import com.kevin.legion.navigation.NavState
import com.kevin.legion.navigation.NavTurn
import com.kevin.legion.navigation.SpeedLimit
import com.kevin.legion.navigation.SpeedUnit
import com.kevin.legion.navigation.resolve.Candidate
import com.kevin.legion.navigation.resolve.SourceKind
import com.kevin.legion.ui.navigation.ChoiceUi
import com.kevin.legion.ui.navigation.NavActions
import com.kevin.legion.ui.navigation.NavContent
import com.kevin.legion.ui.navigation.NavPanel
import com.kevin.legion.ui.navigation.NavUiState
import com.kevin.legion.ui.theme.soft.SoftColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The nav screen's states (mapbox-nav ticket 10, layout A of ticket 06), rendered with a plain
 * stand-in for the map: the Maps SDK needs its native library, so the map slot is a flat box. What
 * this proves is the overlay: banner, "Then:" strip, sheet, tiles, the preview rows, and the words
 * on the not-set-up, arrived and ended sheets. Same runner, graphics mode and device profile as
 * every other screenshot test; baselines live in `app/src/test/snapshots/`.
 *
 * It also drives every state through composition, so a layout or scope error fails the build even
 * when nobody records a baseline.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class NavScreenScreenshotTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val home = NavDestination("Home", 29.7, -95.4)
    private val shell = NavDestination("Shell on Westheimer", 29.74, -95.45)
    private val actions = NavActions(
        onBack = {}, onOpenSetup = {}, onInput = {}, onSubmit = {}, onNavigateTo = {}, onPickCandidate = {},
        onDismissChoice = {}, onPickRoute = {}, onStart = {}, onCancelPreview = {}, onEnd = {}, onDismiss = {},
        onToggleTolls = {}, onToggleMute = {}, onOverviewOrRecenter = {}, onOpenPanel = {}, onClosePanel = {},
        onStopInput = {}, onAddStop = {}, onDropStop = {},
    )

    private fun route(id: String, dur: Double, dist: Double, via: String, tolls: Boolean?) = NavRouteInfo(
        id = id, durationS = dur, distanceM = dist, typicalDurationS = null, via = via, hasTolls = tolls,
        waypoints = emptyList(), excluded = emptySet(), trafficSummary = null,
    )

    private val routes = listOf(
        route("a", 1080.0, 15_100.0, "I-69 S", true),
        route("b", 1260.0, 15_600.0, "Kirby Dr", false),
        route("c", 1380.0, 13_200.0, "Westheimer Rd", true),
    )

    private val guidance = GuidanceSnapshot(
        durationLeftS = 1080.0,
        distanceLeftM = 15_100.0,
        arrivalAtMs = 1_790_000_000_000L,
        turn = NavTurn("Turn left onto Kirby Dr", 480.0, "turn", "left"),
        then = "merge onto I-69 S",
        road = "Main St",
        speedLimit = SpeedLimit(35, SpeedUnit.MPH),
        traffic = "About 4 min slower than usual.",
    )

    private fun shot(name: String, ui: NavUiState) {
        composeTestRule.setContent {
            NavContent(ui, actions) { modifier, _ -> Box(modifier.background(SoftColors.barLow)) }
        }
        composeTestRule.onRoot().captureRoboImage(name)
    }

    private fun idle() = NavState(NavPhase.IDLE, "Ready. Nothing is navigating.")

    @Test fun `choosing a destination`() = shot(
        "nav-choose.png",
        NavUiState(idle(), input = "", savedPlaceLabels = listOf("home", "work", "the gym")),
    )

    @Test fun `a lookup that found nothing says so in words`() = shot(
        "nav-choose-not-found.png",
        NavUiState(
            idle(),
            input = "my next appointment",
            problem = "\"Dentist\" is on your calendar but has no location, so I have nowhere to take you.",
            savedPlaceLabels = listOf("home"),
        ),
    )

    @Test fun `several places match`() = shot(
        "nav-choose-several.png",
        NavUiState(
            idle(),
            input = "pearl",
            choice = ChoiceUi(
                forStop = false,
                query = "pearl",
                candidates = listOf(
                    Candidate("Pearl Cafe", "1 Main St", 29.7, -95.4, 800.0, SourceKind.SEARCH),
                    Candidate("Pearl Diner", "9 Oak St", 29.8, -95.5, 5_200.0, SourceKind.SEARCH),
                ),
                notes = listOf("Couldn't read contacts: contacts can't be read (contacts permission isn't granted)."),
            ),
        ),
    )

    @Test fun `route preview`() = shot(
        "nav-preview.png",
        NavUiState(NavState(NavPhase.PREVIEW, "Routes", destination = home, routes = routes)),
    )

    @Test fun `route preview with an alternative selected and a stop`() = shot(
        "nav-preview-alt.png",
        NavUiState(
            NavState(
                NavPhase.PREVIEW, "Routes", destination = home, stops = listOf(shell), routes = routes,
                selectedRoute = 1, avoid = setOf(AvoidKind.TOLLS),
            ),
        ),
    )

    @Test fun `guiding`() = shot(
        "nav-guiding.png",
        NavUiState(
            NavState(NavPhase.GUIDING, "Navigating to Home.", destination = home, routes = routes, guidance = guidance),
        ),
    )

    @Test fun `guiding muted with a stop and no tolls`() = shot(
        "nav-guiding-muted.png",
        NavUiState(
            NavState(
                NavPhase.GUIDING, "Navigating to Home.", destination = home, stops = listOf(shell), routes = routes,
                muted = true, avoid = setOf(AvoidKind.TOLLS), guidance = guidance,
            ),
        ),
    )

    @Test fun `guiding with unknowns shows no zeros`() = shot(
        "nav-guiding-unknown.png",
        NavUiState(
            NavState(
                NavPhase.GUIDING, "Navigating to Home.", destination = home, routes = routes.take(1),
                guidance = GuidanceSnapshot(null, null, null, null, null, null, null, null),
            ),
        ),
    )

    @Test fun `guiding with the stops panel open`() = shot(
        "nav-guiding-stops.png",
        NavUiState(
            NavState(
                NavPhase.GUIDING, "Navigating to Home.", destination = home, stops = listOf(shell),
                routes = routes, guidance = guidance,
            ),
            panel = NavPanel.STOPS,
            stopInput = "gas station",
        ),
    )

    @Test fun `guiding with the routes panel open`() = shot(
        "nav-guiding-routes.png",
        NavUiState(
            NavState(NavPhase.GUIDING, "Navigating to Home.", destination = home, routes = routes, guidance = guidance),
            panel = NavPanel.ROUTES,
        ),
    )

    @Test fun `rerouting`() = shot(
        "nav-rerouting.png",
        NavUiState(
            NavState(
                NavPhase.GUIDING, "Navigating to Home.", destination = home, routes = routes,
                rerouting = true, guidance = guidance,
            ),
        ),
    )

    @Test fun `a reroute that found nothing is said`() = shot(
        "nav-reroute-failed.png",
        NavUiState(
            NavState(
                NavPhase.GUIDING, "Navigating to Home.", destination = home, routes = routes, guidance = guidance,
                notice = "Could not find a new route. Keeping the last one; this usually means no connection.",
            ),
        ),
    )

    @Test fun `arrived`() = shot(
        "nav-arrived.png",
        NavUiState(
            NavState(NavPhase.ARRIVED, "You have arrived at Home. ${NavFormat.NOTHING_NAVIGATING}", destination = home),
        ),
    )

    @Test fun `trip ended`() = shot(
        "nav-ended.png",
        NavUiState(
            NavState(NavPhase.ENDED, "Trip ended with 2.1 mi to go. ${NavFormat.NOTHING_NAVIGATING}", destination = home),
        ),
    )

    @Test fun `route failure`() = shot(
        "nav-failed.png",
        NavUiState(
            NavState(NavPhase.FAILED, "No connection, so no route to Home was found.", destination = home),
            input = "home",
        ),
    )

    @Test fun `not set up`() = shot("nav-not-set-up.png", NavUiState(forcedByToken("", rejected = false)))

    @Test fun `token refused`() = shot("nav-token-refused.png", NavUiState(forcedByToken("pk.x", rejected = true)))

    private fun forcedByToken(token: String, rejected: Boolean) =
        NavFormat.stateForToken(MapboxTokenState(token, rejected))!!
}
