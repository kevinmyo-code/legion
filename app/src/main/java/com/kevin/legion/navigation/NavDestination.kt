package com.kevin.legion.navigation

/**
 * A place to route to. Latitude first here; the Mapbox seam swaps to Mapbox's lng,lat.
 *
 * **Held in memory only.** A destination that came from Mapbox search is a temporary geocode (ToS
 * 2.7.2 / 2.10.1): it is never written to Room, prefs or a log. A saved place keeps coming from the
 * user's own GPS fix via `tag_place`.
 */
data class NavDestination(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    /** What it is, so the preview can show "Gas station · 12 Main St" and the user can tell (device-run defect 11). */
    val detail: String? = null,
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}
