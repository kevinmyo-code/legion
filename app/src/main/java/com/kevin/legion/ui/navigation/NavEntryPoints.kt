package com.kevin.legion.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The two ways other screens reach the nav screen (mapbox-nav ticket 10): [open] with nothing
 * chosen (the Fleet screen's Navigate row) and [openFor] a saved place's label (its Navigate
 * action; the label goes through the destination resolver like typed text).
 *
 * A CompositionLocal, provided once by `MainActivity` around the NavHost, rather than a parameter
 * threaded through Fleet's four nested composables: those signatures are keyed in the detekt
 * baseline, and two unrelated screens need the same entry point. The default does nothing, so a
 * screen drawn without the provider (a preview, a screenshot test) stays inert instead of crashing.
 */
data class NavEntryPoints(val open: () -> Unit = {}, val openFor: (String) -> Unit = {})

val LocalNavEntryPoints = staticCompositionLocalOf { NavEntryPoints() }
