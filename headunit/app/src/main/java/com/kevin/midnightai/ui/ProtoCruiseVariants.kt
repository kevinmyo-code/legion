package com.kevin.midnightai.ui

// PROTOTYPE — throwaway, answers ticket 09 (.scratch/car-launcher-market/issues/09-...).
// Two @Preview variants per ticket 05's resolution: city-pop (identity 1, tightened) and a new
// green-CRT-phosphor + American-muscle "KITT" identity (identity 2). Neither is wired to real
// data/state - fake values only, per the /prototype skill. Capture the answer (which one, or both,
// and why) to the ticket on resolution; this file does not ship.
//
// 2026-07-18 pass 3 (Kevin): keep the top half (clock/gauges) as already landed, but reinstate the
// REST of the real Cruise screen's furniture that pass 1's simplification dropped - not a redrawn
// stand-in, the actual widgets/chrome per §13's UI map: the real Cassette composable (reused
// directly, it's `internal` in NowPlayingWidgets.kt), the avatar, the CODES pull tab, the GARAGE
// tab, the gear + logbook binder icon, and the lights-out + app-tray buttons.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaTheme
import com.kevin.midnightai.ui.theme.AriaType

// --- Identity 2's proposed token set (NOT yet in AriaColors - this IS the "is it a real second
// material" question ticket 09 exists to answer). Green CRT phosphor terminal + American muscle:
// deep near-black-green background, one hot phosphor green for readouts, chrome/silver for trim,
// a single warm red for the KITT scanner motif and alert states - never the neon magenta/cyan/amber
// city-pop uses, so the two identities read as opposed, not as re-tinted siblings.
internal object KittColors {
    val Background = Color(0xFF0A120C)
    val Panel = Color(0xFF10190F)
    val PanelBorder = Color(0xFF2A3D26)
    val Phosphor = Color(0xFF39FF6A)
    val PhosphorDim = Color(0xFF1F8A3E)
    val Chrome = Color(0xFFB9C2B4)
    val ScanRed = Color(0xFFE0311C)
}

@Preview(widthDp = 1024, heightDp = 600)
@Composable
private fun ProtoCruiseCityPopTightened() {
    AriaTheme {
        Box(Modifier.fillMaxSize().background(AriaColors.Background).padding(28.dp)) {
            // Layout ported from the KITT variant per Kevin's 2026-07-18 feedback ("I like KITT's
            // layout - avatar top left, clock beside it - apply that to city-pop too"): a left
            // console column (avatar + gauges) beside a main column (clock/weekday pinned top,
            // the real Cassette centered in the remaining space below it). Same skeleton, both
            // identities - only the material and the now-playing widget differ.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    Modifier.fillMaxHeight().width(140.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    ProtoAvatar(AriaColors.Panel, AriaColors.NeonMagenta, size = 112.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProtoGauge("62°", "TEMP", AriaColors.NeonCyan)
                        ProtoGauge("14.2", "VOLTS", AriaColors.Amber)
                        ProtoGauge("41mph", "SPD", AriaColors.NeonMagenta)
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    Text("11:47 PM", style = AriaType.readout.copy(fontSize = 34.sp, color = AriaColors.Amber))
                    Text("金 FRI", style = AriaType.neonLabel.copy(color = AriaColors.TextDim, fontSize = 11.sp))

                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                        // The real Cassette composable (NowPlayingWidgets.kt, internal) - reused
                        // directly, not redrawn.
                        Cassette(
                            playing = true,
                            albumArt = null,
                            variant = CassetteVariant.PACIFIC,
                            titleLabel = "PACIFIC BREEZE ‘82",
                            track = "ミッドナイト・ドライブ",
                            artist = "TATSURO",
                        )
                    }
                }
            }

            // Side pull tabs: CODES (left edge) + GARAGE (right edge).
            ProtoPullTab("CODES", Modifier.align(Alignment.CenterStart), AriaColors.NeonMagenta, AriaColors.Panel)
            ProtoPullTab("GARAGE", Modifier.align(Alignment.CenterEnd), AriaColors.NeonCyan, AriaColors.Panel)

            // Bottom-left: gear + logbook binder. Bottom-right: lights-out + app tray.
            Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProtoIconButton("⚙", AriaColors.TextDim, AriaColors.Panel)
                ProtoIconButton("📓", AriaColors.Amber, AriaColors.Panel)
            }
            Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProtoIconButton("🌙", AriaColors.NeonCyan, AriaColors.Panel)
                ProtoIconButton("▦", AriaColors.TextDim, AriaColors.Panel)
            }
        }
    }
}

@Preview(widthDp = 1024, heightDp = 600)
@Composable
private fun ProtoCruiseKittMuscle() {
    AriaTheme {
        Box(Modifier.fillMaxSize().background(KittColors.Background).padding(28.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // Avatar moved to a side column per Kevin's earlier feedback, gauges stacked
                // below it.
                Column(
                    Modifier.fillMaxHeight().width(140.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    ProtoAvatar(KittColors.Panel, KittColors.Chrome, size = 112.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ProtoKittGauge("062", "TEMP")
                        ProtoKittGauge("14.2", "VOLT")
                        ProtoKittGauge("041", "MPH")
                    }
                }

                // Main console: clock pinned top, scanner bar + now-playing equalizer + transport
                // centered in the remaining space (matches city-pop's centered-HUD placement).
                Column(Modifier.fillMaxSize()) {
                    Text(
                        "23:47:02",
                        style = AriaType.readout.copy(fontFamily = FontFamily.Monospace, fontSize = 34.sp, color = KittColors.Phosphor),
                    )
                    Text("FRI 07.18", style = AriaType.neonLabel.copy(color = KittColors.PhosphorDim, fontSize = 11.sp))

                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                        val scanPhase = animPhase(periodMs = 1400)
                        Box(
                            Modifier
                                .fillMaxWidth(0.5f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(KittColors.Panel)
                                .border(1.dp, KittColors.PanelBorder, RoundedCornerShape(2.dp)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.18f)
                                    .align(Alignment.CenterStart)
                                    .padding(start = (triangle(scanPhase) * 220).dp)
                                    .background(KittColors.ScanRed)
                            )
                        }

                        Spacer(Modifier.height(18.dp))

                        Row(
                            Modifier.height(56.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val eqPhase = animPhase(periodMs = 900)
                            repeat(12) { i ->
                                val level = 0.25f + 0.75f * wave(eqPhase, shift = i * 0.09f)
                                Box(
                                    Modifier
                                        .width(10.dp)
                                        .fillMaxHeight(level)
                                        .background(if (i == 11) KittColors.ScanRed else KittColors.Phosphor, RoundedCornerShape(1.dp))
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(KittColors.Panel)
                                .border(1.5.dp, KittColors.Chrome, RoundedCornerShape(2.dp))
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("OUTRUN", style = AriaType.neonLabel.copy(fontWeight = FontWeight.Bold, color = KittColors.Phosphor, fontSize = 13.sp))
                                Text("TRACK 04", style = AriaType.readout.copy(color = KittColors.PhosphorDim, fontSize = 11.sp))
                            }
                            Text("◀◀  ▶  ▶▶", style = AriaType.readout.copy(color = KittColors.Chrome, fontSize = 14.sp))
                        }
                    }
                }
            }

            // Same chrome set as city-pop, KITT material: side pull tabs, corner icon buttons.
            ProtoPullTab("CODES", Modifier.align(Alignment.CenterStart), KittColors.ScanRed, KittColors.Panel)
            ProtoPullTab("GARAGE", Modifier.align(Alignment.CenterEnd), KittColors.Phosphor, KittColors.Panel)
            Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProtoIconButton("⚙", KittColors.Chrome, KittColors.Panel)
                ProtoIconButton("📓", KittColors.Phosphor, KittColors.Panel)
            }
            Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProtoIconButton("🌙", KittColors.Phosphor, KittColors.Panel)
                ProtoIconButton("▦", KittColors.Chrome, KittColors.Panel)
            }
        }
    }
}

@Composable
private fun ProtoAvatar(panelColor: Color, ringColor: Color, size: androidx.compose.ui.unit.Dp = 96.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(panelColor)
            .border(2.dp, ringColor, CircleShape)
    )
}

@Composable
private fun ProtoPullTab(label: String, modifier: Modifier, textColor: Color, bgColor: Color) {
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, textColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 14.dp),
    ) {
        Text(
            label,
            style = AriaType.neonLabel.copy(color = textColor, fontSize = 10.sp),
        )
    }
}

@Composable
private fun ProtoIconButton(glyph: String, color: Color, bgColor: Color) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, color.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = AriaType.readout.copy(fontSize = 16.sp, color = color))
    }
}

@Composable
private fun ProtoGauge(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = AriaType.readout.copy(color = color, fontSize = 16.sp))
        Text(label, style = AriaType.neonLabel.copy(color = AriaColors.TextDim, fontSize = 9.sp))
    }
}

@Composable
private fun ProtoKittGauge(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = AriaType.readout.copy(fontFamily = FontFamily.Monospace, color = KittColors.Phosphor, fontSize = 16.sp))
        Text(label, style = AriaType.neonLabel.copy(color = KittColors.PhosphorDim, fontSize = 9.sp))
    }
}
