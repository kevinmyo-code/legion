package com.kevin.legion.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.MidnightApplication
import com.kevin.legion.R
import com.kevin.legion.navigation.NavCameraMode
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import androidx.compose.foundation.clickable

/**
 * `navigate` - the nav screen of mapbox-nav ticket 06 (layout A: turn banner on top, bottom sheet,
 * the design of record in `research/06-nav-screen-prototypes/nav-prototypes.html`), built in ticket
 * 10. Replaces the spike screen. Stateful wrapper: owns [NavViewModel] and hands the map its feed.
 *
 * **Leaving this screen does not end a trip** (ticket 07); the controller is app-owned and the
 * SDK's own foreground service carries guidance. The trip is on the screen again the moment it is
 * reopened, from the same controller state.
 *
 * [initialPlace] is a saved place's Navigate action: it goes through the same resolver as a typed
 * phrase (saved places first), so "Navigate" on a place and typing its name are one path.
 */
@Composable
fun NavScreen(
    initialPlace: String?,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    viewModel: NavViewModel = viewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val feed = (LocalContext.current.applicationContext as MidnightApplication).navMapFeed
    LaunchedEffect(Unit) { viewModel.onScreenEntered() }
    // Once per screen entry, not once per composition: coming back from Setup must not re-resolve it.
    var placeHandled by rememberSaveable(initialPlace) { mutableStateOf(false) }
    LaunchedEffect(initialPlace) {
        if (!placeHandled && !initialPlace.isNullOrBlank()) {
            placeHandled = true
            viewModel.navigateTo(initialPlace)
        }
    }
    val actions = NavActions(
        onBack = onBack,
        onOpenSetup = onOpenSetup,
        onInput = viewModel::onInput,
        onSubmit = viewModel::submit,
        onNavigateTo = viewModel::navigateTo,
        onPickCandidate = viewModel::pickCandidate,
        onDismissChoice = viewModel::dismissChoice,
        onPickRoute = viewModel::pickRoute,
        onStart = viewModel::start,
        onCancelPreview = viewModel::cancelPreview,
        onEnd = viewModel::end,
        onDismiss = viewModel::dismiss,
        onToggleTolls = viewModel::toggleTolls,
        onToggleMute = viewModel::toggleMute,
        onOverviewOrRecenter = viewModel::overviewOrRecenter,
        onOpenPanel = viewModel::openPanel,
        onClosePanel = viewModel::closePanel,
        onStopInput = viewModel::onStopInput,
        onAddStop = viewModel::addStop,
        onDropStop = viewModel::dropStop,
    )
    NavContent(ui, actions) { modifier ->
        NavMap(
            feed = feed,
            phase = ui.nav.phase,
            selectedRoute = ui.nav.selectedRoute,
            camera = ui.nav.camera,
            onAuthFailure = viewModel::onMapAuthFailure,
            onCameraDetached = viewModel::onCameraDetached,
            modifier = modifier,
        )
    }
}

/**
 * The stateless render. [map] is a slot so a test or preview can draw without the native Maps
 * library. With no Mapbox token the map is NOT created at all (a MapView with no token draws a
 * blank surface): the screen says what is missing, in words, with a way to Setup.
 */
@Composable
fun NavContent(ui: NavUiState, actions: NavActions, map: @Composable (Modifier) -> Unit) {
    SoftTheme {
        Box(Modifier.fillMaxSize().background(SoftColors.ground)) {
            val phase = ui.nav.phase
            if (phase == NavPhase.NOT_SET_UP || phase == NavPhase.TOKEN_REFUSED) {
                NotSetUp(ui.nav.message, actions)
                return@Box
            }
            map(Modifier.fillMaxSize())
            when (phase) {
                NavPhase.GUIDING -> GuidingOverlay(ui, actions)
                NavPhase.PREVIEW -> {
                    BackButton(actions.onCancelPreview, Modifier.align(Alignment.TopStart))
                    MapFab(ui, actions, Modifier.align(Alignment.TopEnd))
                    PreviewSheet(ui, actions, Modifier.align(Alignment.BottomCenter))
                }
                NavPhase.ARRIVED, NavPhase.ENDED -> {
                    BackButton(actions.onBack, Modifier.align(Alignment.TopStart))
                    EndedSheet(ui, actions, Modifier.align(Alignment.BottomCenter))
                }
                else -> {
                    BackButton(actions.onBack, Modifier.align(Alignment.TopStart))
                    ChooseSheet(ui, actions, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Composable
private fun NotSetUp(message: String, actions: NavActions) {
    val accent = AreaAccent.FLEET
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(56.dp).background(accent.container, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            MsIcon(accent.icon, contentDescription = null, tint = accent.onContainer, size = 28.dp)
        }
        Text(
            if (message == NavFormat.TOKEN_REFUSED) "Mapbox refused the token" else "Navigation isn't set up",
            style = MaterialTheme.typography.headlineMedium,
            color = SoftColors.text,
        )
        Text(
            if (message == NavFormat.TOKEN_REFUSED) {
                "LEGION could not use the Mapbox token it has, so there is no map and no directions. " +
                    "Check it in Setup. ${NavFormat.NOTHING_NAVIGATING}"
            } else {
                "LEGION needs a Mapbox token to draw maps and give directions. ${NavFormat.NOTHING_NAVIGATING}"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = SoftColors.text2,
        )
        Button(
            onClick = actions.onOpenSetup,
            colors = ButtonDefaults.buttonColors(
                containerColor = SoftColors.primaryContainer,
                contentColor = SoftColors.onPrimaryContainer,
            ),
        ) { Text("Add a token in Setup") }
        Text(
            "Back",
            style = MaterialTheme.typography.labelLarge,
            color = SoftColors.text2,
            modifier = Modifier.clickable(onClick = actions.onBack).padding(vertical = 8.dp),
        )
    }
}

/** A round button over the map: back, and the overview / recenter toggle. */
@Composable
internal fun RoundMapButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(12.dp)
            .size(48.dp)
            .background(SoftColors.card, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MsIcon(icon, contentDescription = description, tint = SoftColors.text, size = 24.dp)
    }
}

@Composable
private fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    RoundMapButton(R.drawable.ms_arrow_back, "Back", onClick, modifier.statusBarsPadding())
}

/**
 * The overview / recenter floating button (ticket 06: "a small floating button on the map, added at
 * build"): frames the whole route while following, and puts the camera back on the user otherwise.
 */
@Composable
private fun MapFab(
    ui: NavUiState,
    actions: NavActions,
    modifier: Modifier = Modifier,
    withStatusBarPadding: Boolean = true,
) {
    val following = ui.nav.camera == NavCameraMode.FOLLOWING && ui.nav.phase == NavPhase.GUIDING
    RoundMapButton(
        icon = if (following || ui.nav.phase == NavPhase.PREVIEW) R.drawable.ms_nav_fit else R.drawable.ms_navigation,
        description = if (following || ui.nav.phase == NavPhase.PREVIEW) "Show the whole route" else "Recenter on me",
        onClick = actions.onOverviewOrRecenter,
        modifier = if (withStatusBarPadding) modifier.statusBarsPadding() else modifier,
    )
}

@Composable
private fun GuidingOverlay(ui: NavUiState, actions: NavActions) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
            TurnBanner(ui.nav)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ThenStrip(ui.nav.guidance?.then)
                if (ui.nav.muted) MutedTag()
            }
        }
        // Under the banner, on the right: the overview / recenter button, then a way off the screen
        // (the trip keeps going; the SDK's notification is the sign it is running).
        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 128.dp)) {
            MapFab(ui, actions, withStatusBarPadding = false)
            RoundMapButton(R.drawable.ms_arrow_back, "Leave the map; the trip keeps going", actions.onBack)
        }
        GuidingSheet(ui, actions, Modifier.align(Alignment.BottomCenter))
    }
}
