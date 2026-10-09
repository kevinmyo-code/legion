package com.kevin.legion.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.navigation.NavFormat
import com.kevin.legion.navigation.NavState
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The guiding sheet, Google Maps style (2026-10-09: the old one was ~half the map). **Collapsed** it is one
 * row: time left, distance and arrival, and End. **Expanded** (tap the handle or row, or drag it up) it also
 * shows the road, traffic, the stops and routes panels and the four tiles. A panel being open, or a stop
 * lookup needing an answer, forces it open: the panel would otherwise be unreachable.
 *
 * [onCollapsedHeightPx] reports the height of the collapsed part, bottom padding included. The map pads
 * its camera by THAT, whether the sheet is open or not, so the puck and the road ahead are never behind
 * the collapsed sheet and opening it does not make the camera jump.
 */
@Composable
internal fun GuidingSheet(
    ui: NavUiState,
    actions: NavActions,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCollapsedHeightPx: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nav = ui.nav
    val open = expanded || ui.panel != NavPanel.NONE || ui.choice?.forStop == true
    val maxSheet = LocalConfiguration.current.screenHeightDp.dp * SHEET_MAX_SHARE
    val drag = rememberDraggableState { }
    Column(modifier.fillMaxWidth().background(SoftColors.card, SheetShape)) {
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { onCollapsedHeightPx(it.height) }
                .draggable(
                    drag,
                    Orientation.Vertical,
                    onDragStopped = { v ->
                        if (v < -DRAG_FLING_PX) {
                            onExpandedChange(true)
                        } else if (v > DRAG_FLING_PX) {
                            onExpandedChange(false)
                        }
                    },
                )
                .clickable { onExpandedChange(!open) }
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .background(SoftColors.outline, RoundedCornerShape(2.dp))
                    .align(Alignment.CenterHorizontally),
            )
            EtaRow(nav, actions.onEnd)
            // What the user must be told stays in view when collapsed (a reroute, a refused change).
            SheetNotes(nav, ui.problem)
        }
        if (open) GuidingDetails(ui, actions, Modifier.heightIn(max = maxSheet))
    }
}

/** Collapsed row: time left, then distance and arrival, and End. */
@Composable
private fun EtaRow(nav: NavState, onEnd: () -> Unit) {
    val g = nav.guidance
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                g?.durationLeftS?.let { NavFormat.duration(it) } ?: "Time unknown",
                style = MaterialTheme.typography.titleLarge,
                color = SoftColors.good,
            )
            Text(
                infoLine(nav),
                style = MaterialTheme.typography.bodySmall,
                color = SoftColors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(
            onClick = onEnd,
            colors = ButtonDefaults.buttonColors(
                containerColor = SoftColors.alertContainer,
                contentColor = SoftColors.onAlert,
            ),
        ) { Text("End") }
    }
}

@Composable
private fun SheetNotes(nav: NavState, problem: String?) {
    listOfNotNull(nav.note, nav.notice, problem).forEach {
        Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.caution, maxLines = 2)
    }
}

/** What the expanded sheet adds under the collapsed row: the road, traffic, the panels and the four tiles. */
@Composable
private fun GuidingDetails(ui: NavUiState, actions: NavActions, modifier: Modifier) {
    val nav = ui.nav
    val g = nav.guidance
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val onRoad = listOfNotNull(
            g?.road?.let { "On $it" },
            NavFormat.speedLimit(g?.speedLimit)?.let { "limit $it" },
        ).joinToString(" · ")
        if (onRoad.isNotEmpty()) {
            Text(onRoad, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
        }
        g?.traffic?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SoftColors.text2) }
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
                cell,
            )
        }
    }
}

/** A fling faster than this (px/s) opens or closes the sheet. */
private const val DRAG_FLING_PX = 400f

