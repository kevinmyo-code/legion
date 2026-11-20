package com.kevin.midnightai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kevin.midnightai.diag.InputProbe
import com.kevin.midnightai.diag.PANEL_LINES
import com.kevin.midnightai.diag.ProbeLine
import com.kevin.midnightai.media.VolumeController
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/*
 * Developer tools: the Volume check (HU-1 T2) and Wheel keys (HU-1 T9) panels. Probes only: they
 * show what the unit delivers, live, and the same lines go to the drive log (VOLPROBE / WHEELKEY)
 * for the after-drive upload. The volume fix and a wheel-key mapping come after one drive's log.
 */

/** Volume check: the live STREAM_MUSIC index, one button per re-apply strategy, the last events. */
@Composable
internal fun VolumeCheckPanel(context: Context) {
    var open by remember { mutableStateOf(false) }
    var readout by remember { mutableStateOf("") }
    LaunchedEffect(open) {
        while (open) {
            readout = runCatching { VolumeController.readout(context) }.getOrDefault("STREAM_MUSIC not readable")
            delay(1000)
        }
    }
    SettingsRowCard(SettingsRowSpec(
        "Volume check",
        if (open) readout else "Every volume key, index change and who made it. Re-apply buttons try each way of telling the amp.",
        RowAction.Button(if (open) "CLOSE" else "OPEN", ButtonWeight.QUIET) { open = !open },
    ))
    if (!open) return
    Spacer(Modifier.height(AriaDimens.s2))
    Text(
        "Set the volume with the head unit's own keys, then tap each button below and listen. " +
            "The one that changes the sound is the call the amp follows.",
        style = RowSubtitle,
        color = AriaColors.TextDim,
    )
    VolumeController.Reapply.entries.forEach { strategy ->
        Spacer(Modifier.height(AriaDimens.s2))
        SettingsRowCard(SettingsRowSpec(
            strategy.label,
            strategy.what,
            RowAction.Button("TRY", ButtonWeight.PRIMARY) {
                runCatching { VolumeController.reapply(context, strategy) }
                readout = runCatching { VolumeController.readout(context) }.getOrDefault(readout)
            },
        ))
    }
    Spacer(Modifier.height(AriaDimens.s2))
    val lines by InputProbe.volume.collectAsState()
    ProbeLines(lines, empty = "No volume events yet. Press a volume key.")
}

@Composable
private fun ProbeLines(lines: List<ProbeLine>, empty: String) {
    val shape = RoundedCornerShape(4.dp)
    val stamp = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AriaColors.Panel)
            .border(AriaDimens.hairline, AriaColors.PanelBorder, shape)
            .padding(horizontal = AriaDimens.s3, vertical = AriaDimens.s2),
    ) {
        Text("Last $PANEL_LINES, newest at the bottom", style = RowSubtitle, color = AriaColors.TextDim)
        Spacer(Modifier.height(AriaDimens.s1))
        if (lines.isEmpty()) {
            Text(empty, style = RowSubtitle, color = AriaColors.TextPrimary)
        }
        lines.forEach { l ->
            Text("${stamp.format(Date(l.atMs))}  ${l.text}", style = RowSubtitle, color = AriaColors.TextPrimary)
        }
    }
}
