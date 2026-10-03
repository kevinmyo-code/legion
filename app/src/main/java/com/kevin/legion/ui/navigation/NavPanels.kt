package com.kevin.legion.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.NavState
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftColors

// The panels that open inside the guiding and preview sheets (stops, routes) and the "several places
// match" card. Split from NavSheets.kt so each file stays a readable size.

/** The stops panel and the routes panel, shown inside the sheet, plus a stop's own "several places match". */
@Composable
internal fun PanelContent(ui: NavUiState, actions: NavActions) {
    val choice = ui.choice
    if (choice != null && choice.forStop) {
        ChoiceCard(choice, actions)
        return
    }
    when (ui.panel) {
        NavPanel.NONE -> Unit
        NavPanel.STOPS -> StopsPanel(ui, actions)
        NavPanel.ROUTES -> RoutesPanel(ui.nav, actions)
    }
}

@Composable
private fun PanelFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(SoftColors.cardHigh, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
            TextButton(onClick = onClose) { Text("Close") }
        }
        // The body scrolls inside a cap so the sheet's own tiles and Start stay reachable and the map
        // keeps showing (second device run, defect 3).
        val cap = LocalConfiguration.current.screenHeightDp.dp * PANEL_MAX_SHARE
        Column(
            Modifier.heightIn(max = cap).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

private const val PANEL_MAX_SHARE = 0.3f

@Composable
private fun StopsPanel(ui: NavUiState, actions: NavActions) {
    PanelFrame("Stops", actions.onClosePanel) {
        ui.nav.stops.forEach { stop ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stop.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SoftColors.text,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { actions.onDropStop(stop.name) }) { Text("Drop") }
            }
        }
        if (ui.nav.stops.isEmpty()) {
            Text("No stops yet.", style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
        }
        // The button sits BESIDE the field, not under it (device-run defect 6): whatever the window
        // does with the keyboard it keeps the focused field in view, and the button rides with it. The
        // keyboard's Go key does the same job (it always did).
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = ui.stopInput,
                onValueChange = actions.onStopInput,
                label = { Text("Add a stop, e.g. gas station") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { actions.onAddStop() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = actions.onAddStop, enabled = ui.stopInput.isNotBlank() && !ui.resolving) {
                Text("Add")
            }
        }
        Text("Searches along your route.", style = MaterialTheme.typography.bodySmall, color = SoftColors.text3)
        if (ui.resolving) {
            Text("Looking for it.", style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
        }
    }
}

@Composable
private fun RoutesPanel(nav: NavState, actions: NavActions) {
    PanelFrame("Routes", actions.onClosePanel) {
        if (nav.routes.size < 2) {
            Text("No other routes right now.", style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2)
        }
        RouteRows(nav, actions.onPickRoute)
    }
}


@Composable
internal fun RouteRows(nav: NavState, onPick: (Int) -> Unit) {
    val labels = NavFormat.routeLabels(nav.routes)
    val accent = AreaAccent.FLEET
    nav.routes.forEachIndexed { i, r ->
        val selected = i == nav.selectedRoute
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) accent.container else SoftColors.cardHigh, RoundedCornerShape(16.dp))
                .border(2.dp, if (selected) accent.onContainer else SoftColors.cardHigh, RoundedCornerShape(16.dp))
                .clickable { onPick(i) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(labels[i], style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
                Text(
                    NavFormat.routeDetail(r),
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text2,
                    maxLines = 2,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    NavFormat.duration(r.durationS),
                    style = MaterialTheme.typography.titleLarge,
                    color = SoftColors.text,
                )
                Text(
                    NavFormat.distance(r.distanceM),
                    style = MaterialTheme.typography.bodySmall,
                    color = SoftColors.text2,
                )
            }
        }
    }
}


/**
 * Several plausible places (ticket 03's read-back rule): the top pick first with its distance, the
 * others below, a tap says yes. Addresses are shown as text; coordinates never are (ToS 2.7.1).
 */
@Composable
internal fun ChoiceCard(choice: ChoiceUi, actions: NavActions) {
    Column(
        Modifier.fillMaxWidth().background(SoftColors.cardHigh, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Several places match \"${choice.query}\". Which one?",
            style = MaterialTheme.typography.titleMedium,
            color = SoftColors.text,
        )
        choice.candidates.forEachIndexed { i, c ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SoftColors.card, RoundedCornerShape(12.dp))
                    .clickable { actions.onPickCandidate(c) }
                    .padding(12.dp),
            ) {
                Text(
                    if (i == 0) "Top pick: ${c.name}" else c.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SoftColors.text,
                )
                val away = c.distanceM?.let { "${NavFormat.distance(it)} away" }
                val line = listOfNotNull(c.subtitle(), away).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
            }
        }
        choice.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.caution) }
        TextButton(onClick = actions.onDismissChoice) { Text("Cancel") }
    }
}
