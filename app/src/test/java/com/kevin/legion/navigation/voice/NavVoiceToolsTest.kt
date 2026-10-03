package com.kevin.legion.navigation.voice

import com.kevin.legion.navigation.AvoidKind
import com.kevin.legion.navigation.FakeNavSdk
import com.kevin.legion.navigation.FakeTokens
import com.kevin.legion.navigation.GeoPoint
import com.kevin.legion.navigation.MapboxNavController
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.navigation.NavProgressInfo
import com.kevin.legion.navigation.NavTurn
import com.kevin.legion.navigation.RouteFailure
import com.kevin.legion.navigation.RouteRequestResult
import com.kevin.legion.navigation.SpeedLimit
import com.kevin.legion.navigation.SpeedUnit
import com.kevin.legion.navigation.resolve.Candidate
import com.kevin.legion.navigation.resolve.DestinationResolver
import com.kevin.legion.navigation.resolve.DestinationSource
import com.kevin.legion.navigation.resolve.LookupContext
import com.kevin.legion.navigation.resolve.PlaceSearch
import com.kevin.legion.navigation.resolve.SearchAnswer
import com.kevin.legion.navigation.resolve.SearchQuery
import com.kevin.legion.navigation.resolve.SourceAnswer
import com.kevin.legion.navigation.resolve.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phrase to what the (single, fake) local source says about it; a search is never reached when it matches. */
private class ScriptedSource(var answers: Map<String, SourceAnswer>) : DestinationSource {
    override val kind = SourceKind.SAVED_PLACE
    val asked = mutableListOf<String>()

    override suspend fun lookup(query: String, ctx: LookupContext): SourceAnswer {
        asked += query
        return answers.entries.firstOrNull { query.contains(it.key, ignoreCase = true) }?.value ?: SourceAnswer.NoMatch
    }
}

private class NoSearch : PlaceSearch {
    override suspend fun search(query: SearchQuery): SearchAnswer = SearchAnswer.NoMatch
}

/**
 * The four voice tools against a REAL [MapboxNavController] over a fake SDK (the seam the controller's
 * own tests use) and a real [DestinationResolver] over scripted sources. Each result is asserted
 * against what the SDK fake actually holds afterwards, which is the honesty table of ticket 04.
 */
class NavVoiceToolsTest {
    private val sdk = FakeNavSdk().apply { autoReply = FakeNavSdk::honest }
    private val controller = MapboxNavController(
        tokens = FakeTokens(),
        sdkFactory = { sdk },
        fix = { GeoPoint(29.6, -95.3) },
        nowMs = { 1_000_000L },
    )
    private var now = 0L
    private var screenShown = true
    private var screenAsked = 0

    private fun hit(name: String, lat: Double, lng: Double, away: Double) =
        Candidate(name, "1 Main St", lat, lng, away, SourceKind.SAVED_PLACE)

    private fun one(name: String, lat: Double = 29.7, lng: Double = -95.4) =
        SourceAnswer.Hits(listOf(hit(name, lat, lng, 5_000.0)), ambiguous = false)

    private val two = SourceAnswer.Hits(
        listOf(hit("Shell on Main", 29.71, -95.41, 2_000.0), hit("Shell on Kirby", 29.75, -95.45, 6_000.0)),
        ambiguous = true,
    )

    private val source = ScriptedSource(
        mapOf(
            "home" to one("Home"),
            "pharmacy" to one("Pharmacy", 29.72, -95.42),
            "shell" to two,
        ),
    )

    private val tools = NavVoiceTools(
        controller = controller,
        resolver = DestinationResolver(listOf(source), NoSearch()),
        fix = { GeoPoint(29.6, -95.3) },
        screen = { screenAsked++; screenShown },
        nowMs = { now },
    )

    // ------------------------------------------------------------------ navigate

    @Test fun anUnambiguousPlaceStartsAndTheResultIsReadFromTheSdk() = runBlocking {
        val r = tools.navigate("home")
        assertTrue(r.message, r.success)
        assertTrue(r.message, r.message.startsWith("Navigating to Home"))
        assertEquals(NavPhase.GUIDING, controller.state.value.phase)
        assertTrue("the SDK really is running a session", sdk.sessionRunning)
        assertEquals("the nav screen was brought forward once", 1, screenAsked)
    }

    @Test fun avoidIsParsedIntoTheRequest() = runBlocking {
        val r = tools.navigate("home", avoid = "tolls and highways")
        assertTrue(r.message, r.success)
        assertEquals(setOf(AvoidKind.TOLLS, AvoidKind.HIGHWAYS), sdk.requests.last().avoid)
    }

    @Test fun anAvoidItCannotDoIsRefusedAndNothingStarts() = runBlocking {
        val r = tools.navigate("home", avoid = "traffic jams")
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("Nothing changed"))
        assertTrue("no request was made", sdk.calls.isEmpty())
    }

    @Test fun aViaIsResolvedAndRoutedThrough() = runBlocking {
        val r = tools.navigate("home", via = "pharmacy")
        assertTrue(r.message, r.success)
        assertEquals(listOf("Pharmacy"), sdk.requests.last().stops.map { it.name })
    }

    @Test fun previewShowsRoutesAndSaysNothingStarted() = runBlocking {
        val r = tools.navigate("home", preview = true)
        assertTrue(r.message, r.success)
        assertTrue(r.message, r.message.contains("Nothing has started"))
        assertEquals(NavPhase.PREVIEW, controller.state.value.phase)
        assertFalse(sdk.sessionRunning)
        assertEquals(1, screenAsked)
    }

    @Test fun severalPlacesStartNothingAndAskForTheRead() = runBlocking {
        val r = tools.navigate("the shell")
        assertFalse("a read-back is not a start", r.success)
        assertTrue(r.message, r.message.contains("Several places match"))
        assertTrue(r.message, r.message.contains("Top pick: Shell on Main"))
        assertTrue("the others are named", r.message.contains("Shell on Kirby"))
        assertTrue("the distance rides with the pick", r.message.contains("away"))
        assertTrue(r.message, r.message.contains("choice=1"))
        assertTrue(r.message, r.message.contains("Nothing has started"))
        assertTrue("no SDK call at all", sdk.calls.isEmpty())
        assertEquals(NavPhase.IDLE, controller.state.value.phase)
        assertEquals("the screen is not raised for a question", 0, screenAsked)
    }

    @Test fun theYesIsASecondCallWithTheChoiceAndStartsThatPlace() = runBlocking {
        tools.navigate("the shell")
        val r = tools.navigate("the shell", choice = 2)
        assertTrue(r.message, r.success)
        assertEquals("Shell on Kirby", sdk.requests.last().destination.name)
        assertEquals(NavPhase.GUIDING, controller.state.value.phase)
    }

    @Test fun choiceOneIsTheTopPick() = runBlocking {
        tools.navigate("the shell")
        tools.navigate("the shell", choice = 1)
        assertEquals("Shell on Main", sdk.requests.last().destination.name)
    }

    @Test fun aChoiceOutOfRangeSaysSoAndStartsNothing() = runBlocking {
        tools.navigate("the shell")
        val r = tools.navigate("the shell", choice = 9)
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("There is no option 9"))
        assertTrue(sdk.calls.isEmpty())
    }

    @Test fun aChoiceAfterTheWaitHasExpiredAsksAgainRatherThanGuessing() = runBlocking {
        tools.navigate("the shell")
        now += NavVoiceTools.PENDING_TTL_MS + 1
        val r = tools.navigate("the shell", choice = 1)
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("Several places match"))
        assertTrue(sdk.calls.isEmpty())
    }

    @Test fun aChoiceForDifferentWordsIsNotAppliedToThem() = runBlocking {
        tools.navigate("the shell")
        // The user changed their mind about where: the held list belongs to the other phrase.
        val r = tools.navigate("home", choice = 2)
        assertTrue(r.message, r.success)
        assertEquals("Home", sdk.requests.last().destination.name)
    }

    @Test fun anAmbiguousViaAsksAfterTheDestinationAndIsNotLostOnTheAnswer() = runBlocking {
        val asked = tools.navigate("home", via = "the shell")
        assertFalse(asked.success)
        assertTrue(sdk.calls.isEmpty())
        val r = tools.navigate("home", via = "the shell", choice = 2)
        assertTrue(r.message, r.success)
        assertEquals(listOf("Shell on Kirby"), sdk.requests.last().stops.map { it.name })
        assertEquals("Home", sdk.requests.last().destination.name)
    }

    @Test fun anUnknownPlaceSaysWhatWasNotFoundAndNothingStarted() = runBlocking {
        val r = tools.navigate("a place that does not exist")
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("couldn't find"))
        assertTrue(r.message, r.message.contains("Nothing has started"))
        assertTrue(sdk.calls.isEmpty())
    }

    @Test fun noRouteFromMapboxIsAFailureAndNothingIsNavigating() = runBlocking {
        sdk.autoReply = { RouteRequestResult.Failed(RouteFailure.NO_ROUTE, null) }
        val r = tools.navigate("home")
        assertFalse(r.success)
        assertEquals(NavPhase.FAILED, controller.state.value.phase)
        assertFalse(sdk.sessionRunning)
    }

    @Test fun aSessionThatDidNotStartIsNotSuccessEvenThoughTheCallWasMade() = runBlocking {
        sdk.refuseToStart = true
        val r = tools.navigate("home")
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("nothing is navigating"))
    }

    @Test fun whileATripRunsNavigateRefusesAndPointsAtChangeTrip() = runBlocking {
        tools.navigate("home")
        val calls = sdk.calls.size
        val r = tools.navigate("pharmacy")
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("Already navigating to Home"))
        assertEquals("the running trip was not touched", calls, sdk.calls.size)
        assertEquals("Home", controller.state.value.destination?.name)
    }

    @Test fun whenTheMapCannotBeShownGuidanceStillStartsAndTheResultSaysSo() = runBlocking {
        screenShown = false
        val r = tools.navigate("home")
        assertTrue("guidance is running, so this is still a success", r.success)
        assertTrue(r.message, r.message.contains("could not be brought up"))
        assertEquals(NavPhase.GUIDING, controller.state.value.phase)
    }

    @Test fun noDestinationSaysSo() = runBlocking {
        val r = tools.navigate("  ")
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("Nothing has started"))
    }

    // ------------------------------------------------------------------ change_trip

    @Test fun everyChangeWithNoTripSaysNothingIsNavigatingOrDoesItsOwnHarmlessThing() = runBlocking {
        for (action in listOf("add_stop", "avoid", "take_alternative", "remove_stop", "overview", "recenter")) {
            val r = tools.changeTrip(action, place = "pharmacy", avoid = "tolls", route = 2)
            assertFalse("$action with no trip must not succeed", r.success)
            assertTrue("$action: ${r.message}", r.message.contains("Nothing is navigating") ||
                r.message.contains("no extra stops"))
        }
        assertTrue("nothing reached the SDK", sdk.calls.isEmpty())
    }

    @Test fun addStopWhileGuidingSucceedsOnlyBecauseTheNewRoutePassesThroughIt() = runBlocking {
        tools.navigate("home")
        val r = tools.changeTrip("add_stop", place = "pharmacy")
        assertTrue(r.message, r.success)
        assertTrue(r.message, r.message.contains("Added Pharmacy"))
        assertEquals(listOf("Pharmacy"), controller.state.value.stops.map { it.name })
        assertEquals(NavPhase.GUIDING, controller.state.value.phase)
    }

    @Test fun addStopThatTheRouteIgnoresLeavesTheTripUnchangedAndSaysSo() = runBlocking {
        tools.navigate("home")
        // A route that came back without the stop in it.
        sdk.autoReply = { req ->
            val routes = listOf(FakeNavSdk.route("x", req.copy(stops = emptyList())))
            RouteRequestResult.Ready(routes, routes)
        }
        val r = tools.changeTrip("add_stop", place = "pharmacy")
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("unchanged"))
        assertTrue(r.message, r.message.contains("Home"))
        assertTrue(controller.state.value.stops.isEmpty())
    }

    @Test fun addStopWithSeveralMatchesAsksAndChangesNothing() = runBlocking {
        tools.navigate("home")
        val requests = sdk.requests.size
        val r = tools.changeTrip("add_stop", place = "the shell")
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("The trip is unchanged"))
        assertTrue(r.message, r.message.contains("Top pick: Shell on Main"))
        assertEquals(requests, sdk.requests.size)
        val yes = tools.changeTrip("add_stop", place = "the shell", choice = 1)
        assertTrue(yes.message, yes.success)
        assertEquals(listOf("Shell on Main"), controller.state.value.stops.map { it.name })
    }

    @Test fun removeStopDropsItAndAnUnknownOneLeavesTheTripUnchanged() = runBlocking {
        tools.navigate("home")
        tools.changeTrip("add_stop", place = "pharmacy")
        val no = tools.changeTrip("remove_stop", place = "bakery")
        assertFalse(no.success)
        assertTrue(no.message, no.message.contains("The trip is unchanged"))
        val yes = tools.changeTrip("remove_stop")
        assertTrue(yes.message, yes.success)
        assertTrue(controller.state.value.stops.isEmpty())
    }

    @Test fun avoidAddsToWhatIsAlreadyAvoidedAndNoneClearsIt() = runBlocking {
        tools.navigate("home", avoid = "tolls")
        val more = tools.changeTrip("avoid", avoid = "ferries")
        assertTrue(more.message, more.success)
        assertEquals(setOf(AvoidKind.TOLLS, AvoidKind.FERRIES), controller.state.value.avoid)
        val none = tools.changeTrip("avoid", avoid = "none")
        assertTrue(none.message, none.success)
        assertTrue(controller.state.value.avoid.isEmpty())
    }

    @Test fun anAvoidItDoesNotKnowOrOmitsIsRefused() = runBlocking {
        tools.navigate("home")
        assertFalse(tools.changeTrip("avoid", avoid = "potholes").success)
        assertFalse(tools.changeTrip("avoid", avoid = null).success)
    }

    @Test fun takeAlternativeWithTwoRoutesTakesTheOtherOne() = runBlocking {
        tools.navigate("home")
        val firstId = controller.state.value.routes.first().id
        val r = tools.changeTrip("take_alternative")
        assertTrue(r.message, r.success)
        assertEquals("the SDK's primary route changed", false, controller.state.value.routes.first().id == firstId)
    }

    @Test fun takeAlternativeOutOfRangeLeavesTheRouteAlone() = runBlocking {
        tools.navigate("home")
        val firstId = controller.state.value.routes.first().id
        val r = tools.changeTrip("take_alternative", route = 5)
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("There is no route number 5"))
        assertEquals(firstId, controller.state.value.routes.first().id)
    }

    @Test fun muteAndUnmuteChangeCuesOnlyAndSayWhichWayTheyWent() = runBlocking {
        tools.navigate("home")
        val muted = tools.changeTrip("mute")
        assertTrue(muted.success)
        assertTrue(muted.message, muted.message.contains("muted"))
        assertTrue(muted.message, muted.message.contains("assistant still talks"))
        assertTrue(controller.state.value.muted)
        assertTrue(tools.changeTrip("unmute").success)
        assertFalse(controller.state.value.muted)
        assertEquals("a mute is no SDK call", 1, sdk.requests.size)
    }

    @Test fun overviewAndRecenterSucceedOnATripAndRaiseTheScreen() = runBlocking {
        tools.navigate("home")
        val before = screenAsked
        assertTrue(tools.changeTrip("overview").success)
        assertTrue(tools.changeTrip("recenter").success)
        assertEquals(before + 2, screenAsked)
    }

    @Test fun anUnknownActionChangesNothingAndListsTheKnownOnes() = runBlocking {
        val r = tools.changeTrip("teleport")
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("Nothing changed"))
        NavVoiceTools.CHANGE_ACTIONS.forEach { assertTrue(r.message.contains(it)) }
    }

    // ------------------------------------------------------------------ trip_status

    @Test fun statusWithNoTripIsNotNavigatingNeverZeros() {
        for (ask in NavVoiceTools.STATUS_ASKS) {
            val r = tools.tripStatus(ask)
            assertTrue("$ask: ${r.message}", r.message.startsWith("Not navigating"))
            assertFalse("$ask must not invent a number", r.message.any { it.isDigit() })
        }
    }

    @Test fun statusSaysNotNavigatingForAPreviewToo() = runBlocking {
        tools.navigate("home", preview = true)
        val r = tools.tripStatus("time_left")
        assertTrue(r.message, r.message.startsWith("Not navigating"))
        assertTrue(r.message, r.message.contains("previewed"))
    }

    @Test fun statusReadsLiveGuidanceAndSaysUnknownForWhatTheSdkDoesNotHave() = runBlocking {
        tools.navigate("home")
        // Just started: the totals are known, nothing else is yet.
        assertTrue(tools.tripStatus("time_left").message.contains("left to Home"))
        assertTrue(tools.tripStatus("distance_left").message.contains("to Home"))
        assertTrue(tools.tripStatus("arrival").message.contains("Arriving at Home"))
        assertTrue(tools.tripStatus("next_turn").message.contains("don't have the next turn"))
        assertTrue(tools.tripStatus("road").message.contains("don't have the current road"))
        assertTrue(tools.tripStatus("speed_limit").message.contains("don't know the speed limit"))
        assertTrue(tools.tripStatus("traffic").message.isNotBlank())
        // Then the SDK reports progress, and the answers come from it.
        controller.onProgress(
            NavProgressInfo(
                durationLeftS = 600.0,
                distanceLeftM = 4_000.0,
                turn = NavTurn("Turn left onto Main St", 300.0, "turn", "left"),
                then = "Merge onto I-69",
                remainingWaypoints = 1,
                complete = false,
            ),
        )
        controller.onLocation("Westheimer Rd", SpeedLimit(45, SpeedUnit.MPH))
        assertTrue(tools.tripStatus("time_left").message.startsWith("10 min left"))
        val turn = tools.tripStatus("next_turn").message
        assertTrue(turn, turn.contains("Turn left onto Main St"))
        assertTrue(turn, turn.contains("Then: Merge onto I-69"))
        assertTrue(tools.tripStatus("road").message.contains("Westheimer Rd"))
        assertTrue(tools.tripStatus("speed_limit").message.contains("45 mph"))
    }

    @Test fun anUnknownQuestionIsRefusedAndListsTheKnownOnes() {
        val r = tools.tripStatus("weather")
        assertFalse(r.success)
        NavVoiceTools.STATUS_ASKS.forEach { assertTrue(r.message.contains(it)) }
    }

    // ------------------------------------------------------------------ end_trip

    @Test fun endTripWithNothingRunningSaysThereWasNothingToEnd() {
        val r = tools.endTrip()
        assertTrue(r.message, r.message.contains("Nothing to end"))
        assertTrue(sdk.calls.isEmpty())
    }

    @Test fun endTripStopsTheSessionAndSaysSoOnlyBecauseTheSdkConfirms() = runBlocking {
        tools.navigate("home")
        val r = tools.endTrip()
        assertTrue(r.message, r.success)
        assertTrue(r.message, r.message.startsWith("Trip ended"))
        assertFalse(sdk.sessionRunning)
        assertFalse("the instance outlives the trip", sdk.destroyed)
        assertTrue(tools.tripStatus("time_left").message.startsWith("Not navigating"))
    }

    @Test fun endTripThatTheSdkCannotConfirmIsNotSuccess() = runBlocking {
        tools.navigate("home")
        sdk.refuseToStop = true
        val r = tools.endTrip()
        assertFalse(r.success)
        assertTrue(r.message, r.message.contains("could not confirm"))
    }

    @Test fun endTripOnAPreviewClearsItAndDoesNotCallItAnEndedTrip() = runBlocking {
        tools.navigate("home", preview = true)
        val r = tools.endTrip()
        assertTrue(r.message, r.message.contains("cleared the route preview"))
        assertFalse(r.message.startsWith("Trip ended"))
    }
}
