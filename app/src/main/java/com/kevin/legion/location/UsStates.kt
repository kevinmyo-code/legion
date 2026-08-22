package com.kevin.legion.location

import java.util.Locale

/**
 * Full US state/territory name (as Android `Geocoder`'s `adminArea` returns it, e.g. "Texas") to
 * its two-letter postal/FEMA/FBI abbreviation ("TX"). Shared by [AreaInfo]'s FEMA disaster category
 * and [CrimeHistory]'s FBI Crime Data Explorer lookup - both APIs key their state filter on the
 * two-letter form, while `Geocoder` only ever gives back the full English name. Locale-dependent on
 * the device returning English admin-area names, same assumption
 * `LiveToolbox.getCurrentLocation`'s own `Geocoder` call already makes.
 */
object UsStates {
    private val NAME_TO_ABBREVIATION: Map<String, String> = mapOf(
        "alabama" to "AL", "alaska" to "AK", "arizona" to "AZ", "arkansas" to "AR",
        "california" to "CA", "colorado" to "CO", "connecticut" to "CT", "delaware" to "DE",
        "florida" to "FL", "georgia" to "GA", "hawaii" to "HI", "idaho" to "ID",
        "illinois" to "IL", "indiana" to "IN", "iowa" to "IA", "kansas" to "KS",
        "kentucky" to "KY", "louisiana" to "LA", "maine" to "ME", "maryland" to "MD",
        "massachusetts" to "MA", "michigan" to "MI", "minnesota" to "MN", "mississippi" to "MS",
        "missouri" to "MO", "montana" to "MT", "nebraska" to "NE", "nevada" to "NV",
        "new hampshire" to "NH", "new jersey" to "NJ", "new mexico" to "NM", "new york" to "NY",
        "north carolina" to "NC", "north dakota" to "ND", "ohio" to "OH", "oklahoma" to "OK",
        "oregon" to "OR", "pennsylvania" to "PA", "rhode island" to "RI", "south carolina" to "SC",
        "south dakota" to "SD", "tennessee" to "TN", "texas" to "TX", "utah" to "UT",
        "vermont" to "VT", "virginia" to "VA", "washington" to "WA", "west virginia" to "WV",
        "wisconsin" to "WI", "wyoming" to "WY", "district of columbia" to "DC",
        "puerto rico" to "PR", "guam" to "GU", "american samoa" to "AS",
        "u.s. virgin islands" to "VI", "virgin islands" to "VI",
        "northern mariana islands" to "MP",
    )

    /** Case-insensitive lookup, e.g. "Texas" or "texas" -> "TX". Null for anything not a
     * recognized US state/territory name (a foreign country, a blank string, a typo). */
    fun abbreviationFor(fullName: String): String? =
        NAME_TO_ABBREVIATION[fullName.trim().lowercase(Locale.US)]
}
