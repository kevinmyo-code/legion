package com.kevin.legion.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.MidnightApplication
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/**
 * "Navigating to X. Tap to return." under the shell's status line while a trip is GUIDING and the
 * nav screen is not showing (mapbox-nav ticket 10, device-run defect 8). A Home press sends the app
 * to its home screen by design (ADR 0050) and the trip keeps running, so without this the only sign
 * of it was the SDK's notification. Draws nothing otherwise; draws nothing where there is no
 * [MidnightApplication] (a screenshot or preview host).
 */
@Composable
fun TripReturnBar(onNavScreen: Boolean, onOpen: () -> Unit) {
    val app = LocalContext.current.applicationContext as? MidnightApplication
    // The phase alone, so the bar recomposes on a phase change and not on every progress tick.
    val phases = remember(app) {
        app?.navController?.state?.map { it.phase }?.distinctUntilChanged() ?: emptyFlow()
    }
    val phase by phases.collectAsStateWithLifecycle(initialValue = null)
    if (app == null || onNavScreen || phase != NavPhase.GUIDING) return
    val destination = app.navController.state.value.destination?.name
    SoftTheme {
        val accent = AreaAccent.FLEET
        Row(
            Modifier
                .fillMaxWidth()
                .background(accent.container)
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Navigating to ${destination ?: "your destination"}",
                style = MaterialTheme.typography.titleSmall,
                color = SoftColors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("Tap to return", style = MaterialTheme.typography.labelLarge, color = accent.onContainer)
        }
    }
}

/**
 * Ticks when the activity gets a MAIN + LAUNCHER start (the icon, or the Navigation SDK's trip
 * notification, whose content intent is the package's launch intent). Provided by `MainActivity`; a
 * local rather than a `LegionShell` parameter because that function's detekt baseline entry is keyed on
 * its exact signature.
 */
val LocalTripResumeNonce = staticCompositionLocalOf { 0 }

/**
 * A launcher-category start with a trip GUIDING lands on the nav screen (device-run defect 8). Does
 * nothing on the initial 0, when nothing is guiding, or when [openNav] is not needed because the nav
 * screen is already showing (the caller's `launchSingleTop` makes a repeat a no-op). A Home press is
 * a different intent category and never reaches here, so it still goes home mid-trip.
 */
@Composable
fun TripResumeEffect(openNav: () -> Unit) {
    val nonce = LocalTripResumeNonce.current
    val app = LocalContext.current.applicationContext as? MidnightApplication
    LaunchedEffect(nonce) {
        if (nonce > 0 && app?.navController?.state?.value?.phase == NavPhase.GUIDING) openNav()
    }
}
