package com.kevin.midnightai.ui

import android.content.Context
import android.os.Build
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
import com.kevin.midnightai.media.VolumePath
import com.kevin.midnightai.media.VolumePathLogic
import com.kevin.midnightai.media.VolumePathStore
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
        // Which ROM this is, once per opening: nothing in the repo records the unit's own build, so
        // the next round of guesses has nothing to aim at (2026-11-23).
        if (open) {
            runCatching {
                InputProbe.volume(
                    "unit ${Build.MANUFACTURER}/${Build.MODEL} android ${Build.VERSION.RELEASE} " +
                        "sdk ${Build.VERSION.SDK_INT} path=${VolumePathStore.path(context).name}",
                )
            }
        }
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
    var said by remember { mutableStateOf<String?>(null) }
    said?.let {
        Spacer(Modifier.height(AriaDimens.s2))
        Text(it, style = RowSubtitle, color = AriaColors.TextPrimary)
    }
    VolumePathSection(context) { said = it }
    Spacer(Modifier.height(AriaDimens.s3))
    Text(
        "Re-apply the SAME index, five ways (the 2026-11-20 probes). Kept because they are the only " +
            "ones that change nothing when they work.",
        style = RowSubtitle,
        color = AriaColors.TextDim,
    )
    VolumeController.Reapply.entries.forEach { strategy ->
        Spacer(Modifier.height(AriaDimens.s2))
        SettingsRowCard(SettingsRowSpec(
            strategy.label,
            strategy.what,
            RowAction.Button("TRY", ButtonWeight.PRIMARY) {
                val pct = runCatching { VolumeController.reapply(context, strategy) }
                said = when {
                    pct.isFailure -> "Android refused the call."
                    pct.getOrNull() == null -> "Music is muted, so nothing was sent: a TRY never unmutes."
                    else -> null
                }
                readout = runCatching { VolumeController.readout(context) }.getOrDefault(readout)
            },
        ))
    }
    Spacer(Modifier.height(AriaDimens.s2))
    val lines by InputProbe.volume.collectAsState()
    ProbeLines(lines, empty = "No volume events yet. Press a volume key.")
}

/**
 * One real notch, each way a press can travel ([VolumePath]), and the pick that makes the one that
 * worked the one every press uses from then on. This is the half of the Volume check panel that can
 * end the volume bug in one sitting: the app cannot hear the car, so only Kevin's ears can say which
 * call reaches the amp, and before today there was no way to keep that answer.
 */
@Composable
private fun VolumePathSection(context: Context, say: (String) -> Unit) {
    var up by remember { mutableStateOf(true) }
    var lastTried by remember { mutableStateOf<VolumePath?>(null) }
    var inUse by remember { mutableStateOf(VolumePathStore.path(context)) }
    var chosen by remember { mutableStateOf(VolumePathStore.chosenByDriver(context)) }
    var through by remember { mutableStateOf(KeyDispatch.letVolumeKeysThrough) }
    Spacer(Modifier.height(AriaDimens.s3))
    Text(
        "Move the volume one real notch, each way a press can travel. Play music, leave the volume " +
            "mid-way, then tap each TRY and listen. The one you HEAR is the call the amp follows: tap " +
            "USE under it and every press, on screen, on the wheel and by voice, goes that way.",
        style = RowSubtitle,
        color = AriaColors.TextDim,
    )
    Spacer(Modifier.height(AriaDimens.s2))
    SettingsRowCard(SettingsRowSpec(
        if (up) "TRY presses go up" else "TRY presses go down",
        "Off = each TRY lowers the volume one notch instead.",
        RowAction.Toggle(up) { up = it },
    ))
    VolumePathLogic.ORDER.forEach { path ->
        Spacer(Modifier.height(AriaDimens.s2))
        SettingsRowCard(SettingsRowSpec(
            path.label,
            path.what,
            RowAction.Button("TRY", ButtonWeight.PRIMARY) {
                lastTried = path
                say(runCatching { VolumeController.probe(context, path, up) }.getOrDefault("Android refused the call."))
            },
            chip = if (path == inUse) SettingsChip("IN USE", SettingsDot.OK) else null,
        ))
    }
    lastTried?.let { tried ->
        Spacer(Modifier.height(AriaDimens.s2))
        SettingsRowCard(SettingsRowSpec(
            "Use ${tried.label} for every press",
            "The last one you tried. Tap USE and the screen keys, the wheel keys and the voice tool all " +
                "go this way, until you pick another.",
            RowAction.Button("USE", ButtonWeight.HOT) {
                VolumePathStore.choose(context, tried)
                inUse = tried
                chosen = true
                say("Every volume press now goes via ${tried.label}.")
            },
        ))
    }
    if (chosen) {
        Spacer(Modifier.height(AriaDimens.s2))
        SettingsRowCard(SettingsRowSpec(
            "Back to the default way",
            "Drops your pick, so presses go via ${VolumePathLogic.DEFAULT.label} again.",
            RowAction.Button("RESET", ButtonWeight.QUIET) {
                VolumePathStore.clear(context)
                inUse = VolumePathLogic.DEFAULT
                chosen = false
                say("Presses go via ${VolumePathLogic.DEFAULT.label} again.")
            },
        ))
    }
    Spacer(Modifier.height(AriaDimens.s2))
    SettingsRowCard(SettingsRowSpec(
        "Let the volume keys through",
        "Stops this app consuming the hardware and wheel volume keys, so the key reaches whatever the " +
            "unit itself does with it. This app has swallowed them since 2026-10-13, so that has never " +
            "been seen. Off again when the launcher restarts.",
        RowAction.Toggle(through) {
            through = it
            KeyDispatch.letVolumeKeysThrough = it
        },
    ))
}

/** Wheel keys: every key event that reached Cruise, newest last. */
@Composable
internal fun WheelKeysPanel() {
    var open by remember { mutableStateOf(false) }
    SettingsRowCard(SettingsRowSpec(
        "Wheel keys",
        "Keys that type nothing (media, volume, arrows, wheel codes) that reach this app while it is " +
            "in front, plus media buttons sent to the mixtape player. Media buttons the car sends to " +
            "Spotify or the phone go to their session and do not show here. Never logs typing.",
        RowAction.Button(if (open) "CLOSE" else "OPEN", ButtonWeight.QUIET) { open = !open },
    ))
    if (!open) return
    Spacer(Modifier.height(AriaDimens.s2))
    val lines by InputProbe.wheel.collectAsState()
    ProbeLines(lines, empty = "No keys yet. Press each steering-wheel key once.")
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
