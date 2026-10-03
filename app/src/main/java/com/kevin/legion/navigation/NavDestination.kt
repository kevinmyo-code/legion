package com.kevin.legion.navigation

import com.kevin.legion.data.local.TaggedPlace

/** A place to route to. Latitude first here; [MapboxNavController] swaps to Mapbox's lng,lat. */
data class NavDestination(val name: String, val latitude: Double, val longitude: Double)

/** The spike's destination rule, pure so the fallback is tested rather than assumed. */
object NavDestinations {
    /** Downtown Houston, the spike's fallback when no place is labelled `home`. */
    val HOUSTON_TEST = NavDestination(name = "downtown Houston (test point)", latitude = 29.7604, longitude = -95.3698)

    /** The saved place labelled `home` (case-insensitive, not deleted) if any, else [HOUSTON_TEST]. */
    fun forSpike(places: List<TaggedPlace>): NavDestination =
        places.firstOrNull { !it.deleted && it.label.trim().equals("home", ignoreCase = true) }
            ?.let { NavDestination(it.label, it.latitude, it.longitude) }
            ?: HOUSTON_TEST
}
