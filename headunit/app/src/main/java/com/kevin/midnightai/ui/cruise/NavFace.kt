package com.kevin.midnightai.ui.cruise

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.data.local.TaggedPlace
import com.kevin.midnightai.location.PlaceController
import com.kevin.midnightai.navigation.GeoPoint
import com.kevin.midnightai.navigation.NavController
import com.kevin.midnightai.navigation.NavDestination
import com.kevin.midnightai.navigation.NavFormat
import com.kevin.midnightai.navigation.NavPhase
import com.kevin.midnightai.navigation.NavRuntime
import com.kevin.midnightai.navigation.NavState
import com.kevin.midnightai.ui.DeckBold
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaType

/**
 * The NAV face (HU-1 T5): the map with the car's phone-fed position, plain turn directions, RECENTER
 * and END while guiding; saved places to start a trip by hand when idle; a route preview with GO and
 * CANCEL. No token, no capability, or any map failure shows words and never a crash; the CAR face
 * is always one tap away.
 */
object NavFaceHost : NavPanelHost {
    @Composable
    override fun Content(modifier: Modifier) {
        if (!NavRuntime.isInitialised) {
            NoTripNavHost.Content(modifier)
            return
        }
        val state by NavRuntime.controller.state.collectAsState()
        LaunchedEffect(Unit) { NavRuntime.controller.refresh() }
        NavFace(state, NavRuntime.controller, modifier)
    }
}

@Composable
internal fun NavFace(state: NavState, controller: NavController, modifier: Modifier) {
    // Review NAV-1: the same 3 s rule as the CAR face, on the fixes the navigator gets.
    val fix by com.kevin.midnightai.location.LocationController.navFixes.collectAsState()
    val lines = com.kevin.midnightai.navigation.NavGuidance.lines(state, rememberFixAgeMs(fix))
    var driving by remember { mutableStateOf(com.kevin.midnightai.update.DrivingState.isDriving()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            driving = com.kevin.midnightai.update.DrivingState.isDriving()
        }
    }
    Column(modifier) {
        when (state.phase) {
            NavPhase.NO_TOKEN -> NavWords("Maps need your phone.", state.message, Modifier.weight(1f))
            NavPhase.UNAVAILABLE -> {
                NavWords("Maps are off on this unit.", state.message, Modifier.weight(1f))
                if (state.message.contains("TRY MAPS AGAIN")) NavKey("TRY MAPS AGAIN") { NavRuntime.tryAgain() }
            }
            else -> {
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp))) {
                    if (controller.mapAllowed()) {
                        NavMap(
                            following = state.following,
                            stale = lines.stale,
                            panAllowed = com.kevin.midnightai.navigation.NavInput.mapPanAllowed(driving),
                            onMovedByHand = controller::mapMovedByHand,
                            onFailed = { kind -> controller.mapFailed(kind) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    lines.banner?.let { TurnBanner(it, lines.stale, Modifier.align(Alignment.TopCenter)) }
                }
                Spacer(Modifier.height(6.dp))
                NavBottom(state, controller, lines.bottom, com.kevin.midnightai.navigation.NavInput.listScrollAllowed(driving))
            }
        }
    }
}

@Composable
private fun TurnBanner(text: String, stale: Boolean, modifier: Modifier) {
    Text(
        text,
        style = AriaType.readout.copy(fontSize = 20.sp, letterSpacing = 0.sp, fontWeight = DeckBold),
        color = if (stale) AriaColors.accentText(AriaColors.Amber) else AriaColors.TextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .padding(6.dp)
            .background(Color(0xE6000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun NavBottom(state: NavState, controller: NavController, guidingLine: String, scrollAllowed: Boolean) {
    when (state.phase) {
        NavPhase.IDLE -> SavedPlaces(controller, state.message, scrollAllowed)
        NavPhase.REQUESTING -> NavLine(state.message)
        NavPhase.PREVIEW -> {
            NavLine(state.message)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavKey("GO") { controller.go() }
                NavKey("CANCEL") { controller.cancel() }
            }
        }
        NavPhase.GUIDING -> {
            NavLine(guidingLine)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.following) NavKey("RECENTER") { controller.recenter() }
                NavKey("END") { controller.end() }
            }
        }
        NavPhase.ARRIVED -> NavLine(state.message)
        NavPhase.FAILED -> {
            NavLine(state.message)
            NavKey("BACK") { controller.cancel() }
        }
        NavPhase.NO_TOKEN, NavPhase.UNAVAILABLE -> Unit
    }
}

/** Saved places on this unit, as keys that start a trip (v1 has no typed search). */
@Composable
private fun SavedPlaces(controller: NavController, idleWords: String, scrollAllowed: Boolean) {
    val context = LocalContext.current
    var places by remember { mutableStateOf<List<TaggedPlace>?>(null) }
    LaunchedEffect(Unit) { places = runCatching { PlaceController.all(context) }.getOrDefault(emptyList()) }
    val list = places
    when {
        list == null -> NavLine("Reading saved places...")
        list.isEmpty() -> NavLine("No saved places yet. Say \"tag this place as home\" when you are there, then pick it here.")
        else -> {
            NavLine(idleWords)
            Row(Modifier.horizontalScroll(rememberScrollState(), enabled = scrollAllowed), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { place ->
                    NavKey(place.label.uppercase()) {
                        controller.navigateTo(NavDestination(place.label, GeoPoint(place.latitude, place.longitude)))
                    }
                }
            }
        }
    }
}

@Composable
private fun NavWords(title: String, line: String, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = AriaType.readout.copy(fontSize = 22.sp, letterSpacing = 0.sp, fontWeight = DeckBold), color = AriaColors.TextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(line, style = AriaType.readout.copy(fontSize = 15.sp, letterSpacing = 0.sp), color = AriaColors.TextDim, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NavLine(text: String) {
    Text(
        text,
        style = AriaType.readout.copy(fontSize = 15.sp, letterSpacing = 0.sp),
        color = AriaColors.TextPrimary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun NavKey(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .heightIn(min = AriaDimens.driveTouchMin)
            .clip(shape)
            .border(1.dp, AriaColors.PanelBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AriaType.readout.copy(fontSize = 16.sp, letterSpacing = 0.sp, fontWeight = DeckBold), color = AriaColors.TextPrimary)
    }
}
