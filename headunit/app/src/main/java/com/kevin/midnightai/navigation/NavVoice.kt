package com.kevin.midnightai.navigation

import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The voice path to the same controller the NAV face uses (HU-1 T5; root CLAUDE.md: every voice
 * capability has a hands path, and both call one controller). v1 navigates to saved places only:
 * the head unit has no place search yet, and the words say so instead of guessing.
 */
object NavVoice {
    /** A saved place's label and position. */
    data class Place(val label: String, val point: GeoPoint)

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

    /** Plans the route; guidance waits for "go" or the GO key. Main thread for the controller. */
    suspend fun navigateTo(controller: NavController, place: Place): NavResult = onMain { done ->
        controller.navigateTo(NavDestination(place.label, place.point), done)
    }

    suspend fun start(controller: NavController): NavResult = onMain { done -> controller.go(done) }

    suspend fun end(controller: NavController): NavResult = onMain { done -> controller.end(done) }

    private suspend fun onMain(call: (done: (NavResult) -> Unit) -> Unit): NavResult =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(ROUTE_WAIT_MS) {
                suspendCancellableCoroutine { cont -> call { if (cont.isActive) cont.resume(it) } }
            } ?: NavResult(false, "The map did not answer in time, so nothing was started.")
        }

    private const val ROUTE_WAIT_MS = 8_000L
}
