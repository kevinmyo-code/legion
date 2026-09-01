package com.kevin.legion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.service.GeneratedViewController
import com.kevin.legion.service.GeneratedViewPayload
import com.kevin.legion.service.GeneratedViewShape
import com.kevin.legion.ui.common.DeckBar
import com.kevin.legion.ui.common.DeckBarChart
import com.kevin.legion.ui.common.DeckBarLabelRow
import com.kevin.legion.ui.common.DeckLineChart
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckPoint
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.centsLabel
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.deckMotionEnabled

/**
 * The collector for [GeneratedViewController]
 * (`.scratch/one-today/issues/06-a-generated-view-for-niche-questions.md`) - a SIBLING to
 * [GlanceCardOverlay] and [VoiceModalHost], mounted beside both in `LegionShell`'s outer Box.
 *
 * **Dismissed explicitly only, no auto-dismiss timer** - matching [VoiceModalHost]'s posture, not
 * [GlanceCardOverlay]'s 7s countdown. [GeneratedViewController]'s own KDoc states why: this is the
 * answer to a question someone just asked, may still be reading or comparing, and a countdown
 * would yank the chart out from under them.
 *
 * Every shape renders with [DeckPane]/[DeckRow]/[DeckBarChart] - the same primitives every other
 * screen in the app uses, per the ticket's "no new visual language" rule. [GeneratedViewPayload.provenanceText]
 * is always printed, never behind an expander (`legion-trust-disclosures-are-not-furniture`), and a
 * payload with nothing to show ([GeneratedViewPayload.isEmpty]) renders an explicit empty line
 * rather than a chart with nothing drawn on it or a total that reads as a real zero.
 */
@Composable
fun GeneratedViewHost() {
    val payload by GeneratedViewController.current.collectAsStateWithLifecycle()
    val motion = deckMotionEnabled()

    var lastShown by remember { mutableStateOf<GeneratedViewPayload?>(null) }
    LaunchedEffect(payload) { if (payload != null) lastShown = payload }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = payload != null,
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
            lastShown?.let { GeneratedView(it, onDismiss = { GeneratedViewController.dismiss() }) }
        }
    }
}

/** The view itself: plain payload in, no controller reference, so it previews. */
@Composable
fun GeneratedView(payload: GeneratedViewPayload, onDismiss: () -> Unit) {
    val sem = LocalLegionSemantics.current
    DeckPane(
        header = payload.title,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        // Never a saved screen - stated in words every time, so nobody mistakes this for a
        // permanent Meters tile (pinning one is explicitly a later ticket).
        Text(
            "Built just now for this question - not a saved screen.",
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Hairline()

        if (payload.isEmpty) {
            Text(
                "Nothing matched this question in your data.",
                style = LegionType.reading,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            )
        } else {
            when (payload.shape) {
                GeneratedViewShape.BAR_SERIES -> {
                    val bars = payload.points.map { DeckBar(label = it.label, value = it.valueCents.toFloat(), valueLabel = centsLabel(it.valueCents)) }
                    DeckBarChart(bars)
                    DeckBarLabelRow(bars)
                }
                GeneratedViewShape.LINE_SERIES -> {
                    // The line kit plots against real timestamps; a generated view only ever
                    // groups by whole month buckets, so an evenly-spaced synthetic index (rather
                    // than each bucket's true epoch millis) is what keeps the buckets evenly
                    // spaced on screen regardless of month length - the label row below is what
                    // actually names each point, not the x position.
                    val series = payload.points.mapIndexed { i, p ->
                        DeckPoint(xMs = i.toLong(), y = p.valueCents.toFloat())
                    }
                    DeckLineChart(
                        series = series,
                        yLabel = { v -> centsLabel(v.toLong()) },
                        xLabels = payload.points.map { it.label },
                    )
                }
                GeneratedViewShape.TOTAL_WITH_ROWS -> {
                    if (payload.totalLabel != null) {
                        Text(
                            payload.totalLabel,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        )
                    }
                    for (row in payload.rows) {
                        DeckRow(label = row.label, value = row.value)
                    }
                }
            }
        }

        Hairline()
        // The disclosure this whole ticket exists to guarantee - what was counted, what was
        // excluded and why, always in words, never behind an expander.
        Text(
            payload.provenanceText,
            style = LegionType.stamp,
            color = sem.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Text(
            "TAP TO DISMISS",
            style = LegionType.stamp,
            color = sem.ghost,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clickable(onClick = onDismiss),
        )
    }
}
