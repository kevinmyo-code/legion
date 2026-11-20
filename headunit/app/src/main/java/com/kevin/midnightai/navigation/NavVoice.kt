package com.kevin.midnightai.navigation

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The voice path to the same controller the NAV face uses (HU-1 T5; root CLAUDE.md: every voice
 * capability has a hands path, and both call one controller). v1 navigates to saved places only:
 * the head unit has no place search yet, and the words say so instead of guessing.
 *
 * Review round edd3a320, item 4:
 * - a call that timed out is CANCELLED on the controller, so a late answer can never start or show
 *   what the driver was just told did not happen;
 * - start_navigation is refused unless navigate_to planned a preview of that same place within
 *   [START_WINDOW_MS] and that preview is still showing. The window stands in for "this
 *   conversation" (a Live session is minutes long; a plan older than a minute is not what the
 *   driver just agreed to). The agreement itself is the model asking; this is the code's half.
 */
object NavVoice {
    /** A saved place's label and position. */
    data class Place(val label: String, val point: GeoPoint)

    private data class Plan(val label: String, val atMs: Long)

    /** How long after navigate_to a "go" may start that plan. */
    const val START_WINDOW_MS = 60_000L
    private const val TIMEOUT_MS = 8_000L

    @Volatile internal var mainContext: CoroutineContext = Dispatchers.Main
    @Volatile internal var timeoutMs: Long = TIMEOUT_MS
    @Volatile internal var nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    @Volatile private var plan: Plan? = null

    /**
     * The saved place the driver named: an exact label first, else the one label the words contain
     * ("take me home" -> home). Two or more candidates, or none, is null: never a guess.
     */
    fun resolve(spoken: String, places: List<Place>): Place? {
        val said = spoken.trim().lowercase()
        if (said.isEmpty()) return null
        places.firstOrNull { it.label.lowercase() == said }?.let { return it }
        val contained = places.filter { Regex("\\b" + Regex.escape(it.label.lowercase()) + "\\b").containsMatchIn(said) }
        return contained.singleOrNull()
    }

    fun notFound(spoken: String, places: List<Place>): String =
        if (places.isEmpty()) {
            "I can only navigate to saved places for now, and none are saved. Say \"tag this place as home\" when you are there."
        } else {
            "I can only navigate to saved places for now, and \"$spoken\" is not one of them. Saved: " +
                places.joinToString { it.label } + "."
        }

    /** Plans the route; guidance waits for "go" (start) or the GO key. */
    suspend fun navigateTo(controller: NavController, place: Place): NavResult {
        plan = null
        val r = onMain(controller, "No route was planned.") { done ->
            controller.navigateTo(NavDestination(place.label, place.point), done)
        }
        if (r.ok) plan = Plan(place.label, nowMs())
        return r
    }

    suspend fun start(controller: NavController): NavResult {
        val p = plan
        val state = controller.state.value
        val fresh = p != null && nowMs() - p.atMs in 0..START_WINDOW_MS
        if (p == null || !fresh || state.phase != NavPhase.PREVIEW || state.destination?.name != p.label) {
            return NavResult(
                false,
                "Nothing to start: ask for a saved place first, and say go within a minute of hearing the route.",
            )
        }
        plan = null
        return onMain(controller, "Guidance was not started.") { done -> controller.go(done) }
    }

    suspend fun end(controller: NavController): NavResult {
        plan = null
        return onMain(controller, "The trip may still be running; tap END on the NAV face.") { done -> controller.end(done) }
    }

    private suspend fun onMain(
        controller: NavController,
        timedOutWords: String,
        call: (done: (NavResult) -> Unit) -> Unit,
    ): NavResult = withContext(mainContext) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont -> call { if (cont.isActive) cont.resume(it) } }
        } ?: run {
            // The answer may still come later: cancel so it can neither start nor show anything.
            controller.cancel()
            NavResult(false, "The map did not answer in time. $timedOutWords")
        }
    }

    internal fun forgetPlanForTest() {
        plan = null
    }

    internal fun resetForTest() {
        mainContext = Dispatchers.Main
        timeoutMs = TIMEOUT_MS
        nowMs = { android.os.SystemClock.elapsedRealtime() }
        plan = null
    }
}
