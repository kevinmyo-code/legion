package com.kevin.legion.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.navigation.AvoidKind
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.NavPhase
import com.kevin.legion.navigation.NavState
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * The sheet. **Scrolls** when its content is taller than the room it is given (device-run defect 6:
 * with the keyboard up, the stops panel's own button was off the bottom of the screen); the caller
 * wraps it in `imePadding()` so the room shrinks by the keyboard.
 */
@Composable
private fun SheetColumn(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SoftColors.card, SheetShape)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(36.dp)
                .height(4.dp)
                .background(SoftColors.outline, RoundedCornerShape(2.dp))
                .align(Alignment.CenterHorizontally),
        )
        content()
    }
}

// ------------------------------------------------------------------ guiding

/** Time left, distance, arrival and End, then the four tiles. */
@Composable
internal fun GuidingSheet(ui: NavUiState, actions: NavActions, modifier: Modifier = Modifier) {
    val nav = ui.nav
    val g = nav.guidance
    SheetColumn(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    g?.durationLeftS?.let { NavFormat.duration(it) } ?: "Time unknown",
                    style = MaterialTheme.typography.headlineLarge,
                    color = SoftColors.good,
                )
                Text(infoLine(nav), style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
            }
            Button(
                onClick = actions.onEnd,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoftColors.alertContainer,
                    contentColor = SoftColors.onAlert,
                ),
            ) { Text("End") }
        }
        val onRoad = listOfNotNull(
            g?.road?.let { "On $it" },
            NavFormat.speedLimit(g?.speedLimit)?.let { "limit $it" },
        ).joinToString(" · ")
        if (onRoad.isNotEmpty()) Text(onRoad, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
        g?.traffic?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2) }
        nav.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.caution) }
        nav.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.caution) }
        ui.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.caution) }
        PanelContent(ui, actions)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val cell = Modifier.weight(1f)
            StopsTile(nav, actions, cell)
            TollsTile(nav, actions, cell)
            Tile(
                R.drawable.ms_nav_list,
                "Routes",
                ui.panel == NavPanel.ROUTES,
                { actions.onOpenPanel(NavPanel.ROUTES) },
                cell,
            )
            Tile(
                if (nav.muted) R.drawable.ms_nav_unmute else R.drawable.ms_nav_mute,
                if (nav.muted) "Unmute" else "Mute",
                nav.muted,
                actions.onToggleMute,
                Modifier.weight(1f),
            )
        }
    }
}

private fun infoLine(nav: NavState): String {
    val g = nav.guidance
    val parts = listOfNotNull(
        g?.distanceLeftM?.let { NavFormat.distance(it) } ?: "distance unknown",
        nav.stops.takeIf { it.isNotEmpty() }?.let { "via ${it.joinToString { s -> s.name }}" },
        g?.arrivalAtMs?.let { "arrive ${NavFormat.arrivalClock(it)}" },
    )
    return parts.joinToString(" · ")
}

/** The stops tile: "Add stop" with none, "Stops" once there are some; both open the stops panel. */
@Composable
private fun StopsTile(nav: NavState, actions: NavActions, modifier: Modifier) {
    Tile(
        R.drawable.ms_add,
        if (nav.stops.isEmpty()) "Add stop" else "Stops",
        nav.stops.isNotEmpty(),
        { actions.onOpenPanel(NavPanel.STOPS) },
        modifier,
    )
}

@Composable
private fun TollsTile(nav: NavState, actions: NavActions, modifier: Modifier) {
    Tile(R.drawable.ms_nav_toll, "No tolls", AvoidKind.TOLLS in nav.avoid, actions.onToggleTolls, modifier)
}

@Composable
private fun Tile(icon: Int, label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(if (on) SoftColors.primaryContainer else SoftColors.cardHigh, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MsIcon(
            icon,
            contentDescription = null,
            tint = if (on) SoftColors.onPrimaryContainer else SoftColors.text,
            size = 22.dp,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) SoftColors.onPrimaryContainer else SoftColors.text,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ preview

/** Destination, up to three routes (selected one in teal), Start. */
@Composable
internal fun PreviewSheet(ui: NavUiState, actions: NavActions, modifier: Modifier = Modifier) {
    val nav = ui.nav
    SheetColumn(modifier) {
        Column {
            Text("TO", style = MaterialTheme.typography.labelMedium, color = SoftColors.text3)
            Text(
                nav.destination?.name.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                color = SoftColors.text,
            )
            // What the destination IS (its category and address), so a search hit can be told apart.
            nav.destination?.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
            }
            nav.stops.takeIf { it.isNotEmpty() }?.let {
                val via = "via ${it.joinToString { s -> s.name }}"
                Text(via, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
            }
        }
        RouteRows(nav, actions.onPickRoute)
        nav.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.caution) }
        ui.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.caution) }
        PanelContent(ui, actions)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TollsTile(nav, actions, Modifier.weight(1f))
            StopsTile(nav, actions, Modifier.weight(1f))
        }
        Button(
            onClick = actions.onStart,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SoftColors.primary, contentColor = SoftColors.ground),
        ) { Text("Start", style = MaterialTheme.typography.titleMedium) }
    }
}

// ------------------------------------------------------------------ arrived / ended

@Composable
internal fun EndedSheet(ui: NavUiState, actions: NavActions, modifier: Modifier = Modifier) {
    val nav = ui.nav
    SheetColumn(modifier) {
        Text(
            if (nav.phase == NavPhase.ARRIVED) "ARRIVED" else "TRIP ENDED",
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.good,
        )
        Text(nav.destination?.name.orEmpty(), style = MaterialTheme.typography.headlineMedium, color = SoftColors.text)
        Text(nav.message, style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
        OutlinedButton(onClick = actions.onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

// ------------------------------------------------------------------ choosing

/** The "Where to?" sheet: typed field (the hands path for `navigate`), saved places, and any lookup's outcome. */
@Composable
internal fun ChooseSheet(ui: NavUiState, actions: NavActions, modifier: Modifier = Modifier) {
    val requesting = ui.nav.phase == NavPhase.REQUESTING
    SheetColumn(modifier) {
        Text("Where to?", style = MaterialTheme.typography.headlineMedium, color = SoftColors.text)
        OutlinedTextField(
            value = ui.input,
            onValueChange = actions.onInput,
            label = { Text("Address, place, or \"my next appointment\"") },
            singleLine = true,
            enabled = !requesting && !ui.resolving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { actions.onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = actions.onSubmit,
            enabled = ui.input.isNotBlank() && !requesting && !ui.resolving,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SoftColors.primary, contentColor = SoftColors.ground),
        ) { Text("Find route") }
        if (ui.resolving || requesting) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = SoftColors.text2)
                Text(
                    listOfNotNull(
                        if (requesting) ui.nav.message else "Looking for \"${ui.input}\".",
                        // Directions does not wait on the map silently: a map that cannot reach Mapbox
                        // means this lookup may be slow or fail for the same reason.
                        "The map could not be reached, so this may be slow.".takeIf {
                            ui.mapStatus == MapStatus.UNREACHABLE
                        },
                    ).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text2,
                )
            }
        }
        val failure = ui.problem ?: ui.nav.message.takeIf { ui.nav.phase == NavPhase.FAILED }
        failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SoftColors.caution) }
        val choice = ui.choice
        if (choice != null && !choice.forStop) {
            ChoiceCard(choice, actions)
        } else if (ui.savedPlaceLabels.isNotEmpty()) {
            Text("Saved places", style = MaterialTheme.typography.labelMedium, color = SoftColors.text3)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.savedPlaceLabels, key = { it }) { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = SoftColors.text,
                        modifier = Modifier
                            .background(SoftColors.cardHigh, RoundedCornerShape(16.dp))
                            .clickable { actions.onNavigateTo(label) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
