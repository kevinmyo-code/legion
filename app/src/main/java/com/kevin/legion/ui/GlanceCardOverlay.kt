package com.kevin.legion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.service.GlanceCardController
import com.kevin.legion.service.GlanceCardPayload
import com.kevin.legion.service.GlanceCell
import com.kevin.legion.service.GlanceRow
import com.kevin.legion.service.GlanceShape
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.deckMotionEnabled
import kotlinx.coroutines.delay

/** How long a card stays up before dismissing itself. See [GlanceCardController]'s KDoc. */
private const val GLANCE_DISMISS_MS = 7_000L

/**
 * The spoken answer, on screen, large enough to read at a glance.
 *
 * **Why this file exists.** [GlanceCardController] and all four of its
 * producers (`get_codes`, `check_readiness`, `get_mpg`, `get_health`) survived
 * the Midnight AI port; the composable that COLLECTS them did not - it went out
 * with the city-pop `ui/` clean slate and was never rebuilt. So every one of
 * those tools has been calling `show()` into a StateFlow nothing was reading,
 * and asking for your codes has been voice-only ever since. Third instance of
 * the same port shape as the BLE dongle and the add-a-car tool: the capability
 * was intact, the caller or the consumer was missing.
 *
 * **The 5s auto-dismiss lives here on purpose**, and [GlanceCardController]'s
 * KDoc says so: keying `LaunchedEffect` on the payload means Compose cancels
 * the previous countdown the instant a new card replaces one still showing, so
 * "newest wins" needs no manual Job bookkeeping. Raised to 7s here - four DTCs
 * with descriptions is more reading than the original number-shaped card, and
 * the driver may be looking at the road.
 *
 * **Readiness monitors are worded, never coloured only** (CLAUDE.md §7). A
 * green tag says READY and a muted one says NOT READY; the colour is the
 * decoration, the word is the state.
 *
 * Mounted once from [LegionShell], last in the outer Box, so it paints over the
 * bottom bar and whatever destination is showing. It used to share that
 * stacking with a boot overlay; boot was dropped 2026-08-14 and this is now the
 * only overlay in that Box.
 */
@Composable
fun GlanceCardOverlay() {
    val payload by GlanceCardController.current.collectAsStateWithLifecycle()
    val motion = deckMotionEnabled()

    // The flow goes null the moment the card is dismissed, but AnimatedVisibility
    // still needs something to draw for the length of the exit slide. Holding the
    // last non-null payload is what makes the card slide out instead of blinking
    // out of existence.
    var lastShown by remember { mutableStateOf<GlanceCardPayload?>(null) }
    LaunchedEffect(payload) { if (payload != null) lastShown = payload }

    // Keyed on the payload, not on a boolean: a second card arriving while the
    // first is still up must restart the countdown, not inherit its remainder.
    LaunchedEffect(payload) {
        if (payload != null) {
            delay(GLANCE_DISMISS_MS)
            GlanceCardController.dismiss()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = payload != null,
            // Pulls up from the bottom edge, the way Midnight's did. Under
            // scale-0 accessibility motion the card still appears and still
            // reads - it just arrives without the slide (ticket 04 answer #5:
            // "it must still render a complete UI").
            enter = if (motion) {
                slideInVertically(tween(220)) { it } + fadeIn(tween(220))
            } else {
                fadeIn(tween(0))
            },
            exit = if (motion) {
                slideOutVertically(tween(180)) { it } + fadeOut(tween(180))
            } else {
                fadeOut(tween(0))
            },
        ) {
            lastShown?.let { GlanceCard(it, onDismiss = { GlanceCardController.dismiss() }) }
        }
    }
}

/** The card itself: plain payload in, no controller reference, so it previews. */
@Composable
fun GlanceCard(payload: GlanceCardPayload, onDismiss: () -> Unit) {
    val sem = LocalLegionSemantics.current
    DeckPane(
        header = payload.title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            // Tap anywhere to dismiss early - the countdown is a floor on how
            // long it stays, not a wait the driver is forced to sit through.
            .clickable(onClick = onDismiss),
    ) {
        if (payload.headline != null) {
            Text(
                payload.headline,
                style = LegionType.reading,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Hairline()
        }

        when (payload.shape) {
            GlanceShape.NUMBER -> {
                Text(
                    payload.rows.firstOrNull()?.value ?: payload.headline.orEmpty(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            }
            GlanceShape.LIST, GlanceShape.HEADLINE_LIST -> {
                for (row in payload.rows) {
                    DeckRow(label = row.label, value = row.value)
                }
            }
            GlanceShape.STATUS_GRID -> {
                // Two per line: monitor names are long ("Evaporative System")
                // and a three-column grid truncated them on a narrow phone.
                for (pair in payload.cells.chunked(2)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (cell in pair) MonitorCell(cell, Modifier.weight(1f))
                        // Keeps a lone trailing cell at half width instead of
                        // letting it stretch across the whole row.
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }

        Text(
            "TAP TO DISMISS",
            style = LegionType.stamp,
            color = sem.ghost,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun MonitorCell(cell: GlanceCell, modifier: Modifier = Modifier) {
    val sem = LocalLegionSemantics.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            cell.label,
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.weight(1f),
        )
        // The word carries the state; the tag style only decorates it.
        DeckTag(
            if (cell.ready) "READY" else "NOT READY",
            if (cell.ready) DeckTagStyle.INVERTED_GREEN else DeckTagStyle.OUTLINE_MUTED,
        )
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Glance: stored codes", widthDp = 360, heightDp = 320)
@Composable
private fun PreviewGlanceCodes() = LegionTheme {
    GlanceCard(
        GlanceCardPayload(
            shape = GlanceShape.LIST,
            title = "Codes",
            rows = listOf(
                GlanceRow("P0420", "Catalyst System Efficiency Below Threshold"),
                GlanceRow("P0301", "Cylinder 1 Misfire Detected"),
            ),
            sourceTool = "get_codes",
        ),
        onDismiss = {},
    )
}

@Preview(name = "Glance: emissions readiness", widthDp = 360, heightDp = 360)
@Composable
private fun PreviewGlanceReadiness() = LegionTheme {
    GlanceCard(
        GlanceCardPayload(
            shape = GlanceShape.STATUS_GRID,
            title = "Readiness",
            headline = "Check engine off · 0 code(s)",
            cells = listOf(
                GlanceCell("Misfire", true),
                GlanceCell("Fuel System", true),
                GlanceCell("Catalyst", false),
                GlanceCell("Evaporative System", false),
                GlanceCell("Oxygen Sensor", true),
            ),
            sourceTool = "check_readiness",
        ),
        onDismiss = {},
    )
}

@Preview(name = "Glance: mpg", widthDp = 360, heightDp = 300)
@Composable
private fun PreviewGlanceMpg() = LegionTheme {
    GlanceCard(
        GlanceCardPayload(
            shape = GlanceShape.HEADLINE_LIST,
            title = "MPG",
            headline = "24.3 mpg this drive",
            rows = listOf(
                GlanceRow("Last drive", "22.8"),
                GlanceRow("2 drives ago", "25.1"),
            ),
            sourceTool = "get_mpg",
        ),
        onDismiss = {},
    )
}
