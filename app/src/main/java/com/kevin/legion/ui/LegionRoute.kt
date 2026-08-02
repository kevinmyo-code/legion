package com.kevin.legion.ui

/**
 * Every route in the single-activity shell (ticket 07 resolution §5). Four
 * top-level tabs plus their absorbed sub-routes - the shape is dictated
 * verbatim by the resolution:
 *
 * ```
 * fleet/       + fleet/places     <- was SavedPlacesActivity
 * ledger/      + ledger/import    <- was LedgerImportActivity
 * pantry/      + pantry/import    <- was PantryImportActivity
 * settings/    + settings/key
 * ```
 *
 * Plain string constants rather than a sealed class with typed args: nothing
 * here takes a navigation argument, so a type-safe route hierarchy would be
 * ceremony with no payoff. Revisit if a sub-route ever needs one (e.g. "open
 * this specific saved place").
 */
object LegionRoute {
    const val FLEET = "fleet"
    const val FLEET_PLACES = "fleet/places"

    const val LEDGER = "ledger"
    const val LEDGER_IMPORT = "ledger/import"

    const val PANTRY = "pantry"
    const val PANTRY_IMPORT = "pantry/import"

    const val SETTINGS = "settings"
    const val SETTINGS_KEY = "settings/key"

    /** The four bottom-nav destinations, in display order. Assistant is NOT one of them - it's a mode, not a place (resolution §5). */
    val TOP_LEVEL = listOf(FLEET, LEDGER, PANTRY, SETTINGS)

    /**
     * The top-level tab [route] belongs to, or null if it belongs to none.
     * A sub-route is its tab's route plus a `/` segment (`fleet/places` under
     * `fleet`), which is what makes the prefix test sufficient - and why the
     * `/` is part of the test rather than a bare `startsWith`, so a future
     * top-level `fleetsomething` could never be swallowed by `fleet`.
     *
     * The bottom bar's selected state is derived through here rather than by
     * exact route equality. With equality, every sub-route lit no tab at all:
     * open `settings/key` and the whole bar went dark, which reads as "you
     * have left the app's navigation" when you have not. Caught on the A17K
     * 2026-08-02.
     */
    fun topLevelOf(route: String?): String? =
        TOP_LEVEL.firstOrNull { route == it || route?.startsWith("$it/") == true }

    /** Short label for a top-level route's bottom-nav item. */
    fun label(route: String): String = when (route) {
        FLEET -> "Fleet"
        LEDGER -> "Ledger"
        PANTRY -> "Pantry"
        SETTINGS -> "Settings"
        else -> route
    }
}
