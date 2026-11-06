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
    /**
     * Which resolver source found it (`saved_place`, `search`, ...), for the NAV_AUDIT `route_request`
     * line's `to_src=`; null when it did not come through the resolver.
     */
    val source: String? = null,
    /**
     * A Mapbox search hit's `mapbox_id`, which ToS 2.7.6 lets LEGION store (map decision D1), for the
     * `route_request` line's `to_id=`. Null for every other source.
     */
    val mapboxId: String? = null,
) {
    val point: GeoPoint get() = GeoPoint(latitude, longitude)
}
