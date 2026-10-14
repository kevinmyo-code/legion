package com.kevin.midnightai.ui

// Ships. Design/timing decided in .scratch/glance-cards/ (three tickets: content model,
// visual design - center modal won over a top band and an instrument-strip takeover -
// and timing/collision lifecycle). Prototype: proto/glance-card-2026-07-23 branch,
// ProtoGlanceCard.kt (three placement variants against real Cruise chrome).
//
// Callers: CruiseScreen and LightsOutScreen, same accent/dimAccent/panel/textColor
// palette pass-through TriviaOverlay already uses - Lights Out passes its dim palette,
// Cruise passes full AriaColors.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.service.GlanceCardController
import com.kevin.midnightai.service.GlanceCardPayload
import com.kevin.midnightai.service.GlanceShape
import com.kevin.midnightai.ui.theme.AriaType
import kotlinx.coroutines.delay

private const val GLANCE_CARD_DURATION_MS = 5_000L

/**
 * Mounts the glance card (if one's showing) over the current screen. Owns the
 * 5s auto-dismiss timer via `LaunchedEffect(payload)` - see
 * [GlanceCardController]'s kdoc for why the controller itself doesn't.
 */
@Composable
fun GlanceCardOverlay(
    modifier: Modifier = Modifier,
    accent: Color,
    dimAccent: Color,
    panel: Color,
    scrim: Color,
    textColor: Color,
) {
    val payload by GlanceCardController.current.collectAsState()
    val p = payload ?: return

    LaunchedEffect(p) {
        delay(GLANCE_CARD_DURATION_MS)
        GlanceCardController.dismiss()
    }

    GlanceCardCenterModal(p, accent, dimAccent, panel, scrim, textColor, modifier)
}

@Composable
private fun GlanceCardCenterModal(
    payload: GlanceCardPayload,
    accent: Color,
    dimAccent: Color,
    panel: Color,
    scrim: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val enter = rememberRamp(400)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scrim.copy(alpha = scrim.alpha * enter))
            .clickable { GlanceCardController.dismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 460.dp, max = 520.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(panel.copy(alpha = 0.94f * enter.coerceAtLeast(0.3f)))
                .border(2.dp, accent.copy(alpha = 0.5f * enter), RoundedCornerShape(14.dp))
                .padding(28.dp),
        ) {
            Text(
                payload.title,
                style = AriaType.neonLabel.copy(fontSize = 20.sp, letterSpacing = 5.sp),
                color = dimAccent,
            )
            if (payload.headline != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    payload.headline,
                    style = AriaType.readout.copy(fontSize = 34.sp),
                    color = accent,
                )
            }
            if (payload.rows.isNotEmpty() || payload.cells.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                GlanceCardBody(payload, accent, textColor)
            }
        }
    }
}

@Composable
private fun GlanceCardBody(payload: GlanceCardPayload, accent: Color, textColor: Color) {
    when (payload.shape) {
        GlanceShape.LIST, GlanceShape.HEADLINE_LIST -> {
            Column {
                payload.rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            row.label,
                            style = AriaType.readout.copy(fontSize = 20.sp),
                            color = textColor.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Text(
                            row.value,
                            style = AriaType.readout.copy(fontSize = 20.sp),
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        GlanceShape.STATUS_GRID -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.height(150.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(payload.cells) { cell ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (cell.ready) accent else textColor.copy(alpha = 0.25f))
                                .height(10.dp)
                                .widthIn(min = 10.dp),
                        )
                        Text(
                            cell.label,
                            style = AriaType.readout.copy(fontSize = 16.sp),
                            color = if (cell.ready) textColor else textColor.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        GlanceShape.NUMBER -> {} // headline alone covers this shape
    }
}
