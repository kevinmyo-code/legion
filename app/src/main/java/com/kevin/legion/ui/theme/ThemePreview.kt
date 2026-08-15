package com.kevin.legion.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.common.DeckBar
import com.kevin.legion.ui.common.DeckBarChart
import com.kevin.legion.ui.common.DeckBezel
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckCheckbox
import com.kevin.legion.ui.common.DeckDialog
import com.kevin.legion.ui.common.DeckFeedRow
import com.kevin.legion.ui.common.DeckLineChart
import com.kevin.legion.ui.common.DeckLineOverlay
import com.kevin.legion.ui.common.DeckMarkerType
import com.kevin.legion.ui.common.DeckMeter
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckPoint
import com.kevin.legion.ui.common.DeckRadio
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckSectionRule
import com.kevin.legion.ui.common.DeckSmallMultiple
import com.kevin.legion.ui.common.DeckSparkline
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.common.QuarantineTag
import com.kevin.legion.ui.common.StatusLine

/**
 * Renderable proof of the VACUUM/SENTRY theme (mission-control ticket 13, rebuilding cyberdeck-ui
 * ticket 12's original) - the L11 gate every later build ticket reads BEFORE wiring a screen to
 * [com.kevin.legion.ui.common.DeckPanels]'s components, per CLAUDE.md §8's L11 entry: a ticket's
 * own verification steps are gates, not notes, and "render the previews before building on the
 * theme" is this ticket's literal instance of that rule.
 *
 * **Rendering itself is DEFERRED to the on-device ship pass for this build** - the execution
 * environment here cannot render a Compose preview, so this file is written and reviewed by eye
 * against ticket 03's dimensioned spec, not confirmed pixel-correct. That gap is carried forward
 * explicitly, not silently, per L11: it is a named, deferred verification step, not a skipped one.
 *
 * These are previews, not components - do not import them into real screens.
 * Everything is hardcoded, same posture as the MILSPEC-era file this replaces: nothing here
 * touches a singleton, a database, Bluetooth, or a file, so these render in the preview JVM
 * without a `LocalInspectionMode` guard (Midnight AI's L1 lesson, carried over).
 */

@Composable
private fun BiometricUplinkDemo() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            StatusLine(left = "SYSTEM STATUS", clock = "14:02:37")
            DeckPane(header = "Burn", headerAccent = "on pace") {
                DeckRow(label = "Intake today", value = "1,230 / 2,400 KCAL")
                DeckRow(
                    label = "Protein",
                    value = "88 / 150 G",
                    tag = { DeckTag("EST", DeckTagStyle.OUTLINE_MUTED) },
                )
                DeckRow(
                    label = "Budget: dining out",
                    value = "245.00 / 200.00 USD",
                    tag = { DeckTag("PACING HOT", DeckTagStyle.INVERTED_AMBER) },
                )
                DeckRow(
                    label = "Oil change interval",
                    value = "ARMED",
                    tag = { DeckTag("ARMED", DeckTagStyle.INVERTED_GREEN) },
                )
                DeckRow(
                    label = "November statement",
                    value = "TOTALS DISAGREE BY 0.02",
                    tag = { QuarantineTag("QUARANTINE") },
                )
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CALORIES · 51% OF TARGET", style = LegionType.stamp, color = LocalLegionSemantics.current.faint)
                    DeckMeter(fraction = 0.51f, paceFraction = 0.62f)
                }
            }
        }
    }
}

@Composable
private fun HeroReadoutDemo() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("BALANCE // DBS MULTIPLIER", style = MaterialTheme.typography.headlineSmall, color = LocalLegionSemantics.current.faint)
            Text("8,900.00", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            Text("SGD · LLM_RECONCILED", style = LegionType.stamp, color = LocalLegionSemantics.current.faint)
        }
    }
}

/**
 * Ticket 13's new shell/chrome primitives, exercised together: the bezel wrapping a status line, a
 * section rule grouping a dense feed, and a run of [DeckFeedRow]s underneath it - the 22dp
 * display-only sibling [DeckRow] can no longer be (ticket 03's "a 22dp feed row cannot be
 * tappable" finding).
 */
@Composable
private fun BezelAndFeedDemo() {
    Surface(color = MaterialTheme.colorScheme.background) {
        DeckBezel {
            Column {
                StatusLine(left = "SYSTEM STATUS", clock = "14:02:37")
                DeckSectionRule(label = "Live PIDs")
                DeckFeedRow(code = "010C", name = "Engine RPM", value = "2,150")
                DeckFeedRow(code = "010D", name = "Vehicle speed", value = "58 MPH")
                DeckFeedRow(code = "0105", name = "Coolant temperature", value = "192 F")
                DeckFeedRow(code = "012F", name = "Fuel level input", value = "61%")
                DeckSectionRule(label = "Reconciled statements")
                DeckFeedRow(code = "DBS", name = "November multiplier statement", value = "8,900.00 SGD")
                DeckFeedRow(code = "BOFA", name = "November checking statement", value = "3,412.09 USD")
            }
        }
    }
}

/**
 * Ticket 13's control vocabulary ([com.kevin.legion.ui.common.DeckControls]), every non-dialog
 * control together on one pane: [DeckSwitch], [DeckCheckbox], [DeckRadio] (two, grouped), a plain
 * [DeckButton], and a [DeckTextField]. State is `remember`ed locally so the preview is genuinely
 * interactive in Studio's interactive-preview mode, not a frozen snapshot - the same posture as
 * [BiometricUplinkDemo]'s meter fraction being a literal, not a knob, except here toggling actually
 * exercises [Modifier.toggleable]/[Modifier.selectable]'s real state machinery rather than a fake one.
 */
@Composable
private fun ControlVocabularyDemo() {
    var switchOn by remember { mutableStateOf(true) }
    var checked by remember { mutableStateOf(false) }
    var radioChoice by remember { mutableStateOf(0) }
    var fieldValue by remember { mutableStateOf("DBS Multiplier") }
    Surface(color = MaterialTheme.colorScheme.background) {
        DeckPane(header = "Controls") {
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DeckSwitch(checked = switchOn, onCheckedChange = { switchOn = it })
                DeckCheckbox(checked = checked, onCheckedChange = { checked = it }, label = "Notify on quarantine")
                DeckRadio(selected = radioChoice == 0, onClick = { radioChoice = 0 }, label = "DBS multiplier")
                DeckRadio(selected = radioChoice == 1, onClick = { radioChoice = 1 }, label = "BofA checking")
                DeckButton(text = "Verify & save", onClick = {})
                DeckTextField(value = fieldValue, onValueChange = { fieldValue = it }, label = "Account label")
            }
        }
    }
}

/**
 * [DeckButton]'s destructive tiers side by side (ticket 04 answer §4, verified visually here since
 * this environment cannot render a screenshot): the everyday `destructive = true` state reads a
 * plain ink outline, indistinguishable in weight from an ordinary control; only `confirming = true`
 * spends full [com.kevin.legion.ui.theme.LegionSemantics.chrome]. A driver should never see the
 * confirming state except as the second half of a two-tap flow ([LedgerScreen]'s `PURGE LEDGER` /
 * `YES, PURGE THE LEDGER` shape) - it is rendered standalone here only so the L11 gate can compare
 * both states at once.
 */
@Composable
private fun DestructiveButtonDemo() {
    Surface(color = MaterialTheme.colorScheme.background) {
        DeckPane(header = "Purge ledger") {
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Delete every imported transaction and rescan the folder from scratch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalLegionSemantics.current.faint,
                )
                DeckButton(text = "Purge ledger", onClick = {}, destructive = true)
                DeckButton(text = "Yes, purge the ledger", onClick = {}, destructive = true, confirming = true)
            }
        }
    }
}

/**
 * [DeckDialog] - a pane with a pill title, opened over [ControlVocabularyDemo]'s own pane so the
 * "inside the bezel" placement reads against real screen content rather than a blank ground.
 * `dialogOpen` starts `true` so a static preview render (not just interactive mode) shows the
 * dialog, matching how [BiometricUplinkDemo] and friends always render their "interesting" state
 * rather than a default-closed one.
 */
@Composable
private fun DialogDemo() {
    var dialogOpen by remember { mutableStateOf(true) }
    Surface(color = MaterialTheme.colorScheme.background) {
        DeckPane(header = "Companions") {
            Column(Modifier.padding(vertical = 6.dp)) {
                DeckButton(text = "Delete Alfred", onClick = { dialogOpen = true }, destructive = true)
            }
        }
        if (dialogOpen) {
            DeckDialog(title = "Delete Alfred?", onDismissRequest = { dialogOpen = false }) {
                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "This removes the profile everywhere it syncs, not just on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalLegionSemantics.current.faint,
                    )
                    DeckButton(text = "Cancel", onClick = { dialogOpen = false })
                    DeckButton(text = "Delete", onClick = { dialogOpen = false }, destructive = true, confirming = true)
                }
            }
        }
    }
}

/**
 * [com.kevin.legion.ui.common.DeckCharts.kt]'s recolour (mission-control ticket 15, resolving
 * ticket 06) exercised together, the L11 gate for that ticket: [DeckSparkline] carrying the full
 * shape-typed marker vocabulary (filled dot / hollow endpoint / diamond / cross, ticket 06 answer
 * #4) at the exact panel-height scale it ships at in [DeckSmallMultiple] and on HOME's own
 * sparklines, and [DeckLineChart]'s one sanctioned two-series overlay (ticket 06 answer #2) - mint
 * ACTUAL against amber BUDGET, both direct-labelled at their own endpoints, never a legend. The
 * [DeckBarChart] beneath proves the dashed-amber-target-against-mint-fill fix in the same glance -
 * this is the exact pairing ticket 06 measured at dE 5.5 under deuteranopia when the target line
 * was still green.
 */
@Composable
private fun ChartKitDemo() {
    val dayMs = 24L * 60 * 60 * 1000L
    val markerSeries = listOf(12f, 14f, 15f, 16f, 15.5f, 17f, 18f, 19f, 17f, 20f)
    val markerTypes = listOf(
        DeckMarkerType.LOGGED, null, DeckMarkerType.ESTIMATE, null,
        DeckMarkerType.PROVISIONAL, null, null, DeckMarkerType.LOGGED, null, DeckMarkerType.ENDPOINT,
    )
    val actual = (0 until 14).map { i -> if (i == 5 || i == 6) null else DeckPoint(xMs = i * dayMs, y = 180f + 12f * i - (i * i)) }
    val budget = (0 until 14).map { i -> DeckPoint(xMs = i * dayMs, y = 208f) }
    val bars = listOf(
        DeckBar("MON", 4200f, targetValue = 5000f, valueLabel = "42.00"),
        DeckBar("TUE", 6100f, targetValue = 5000f, mark = DeckMarkerType.PROVISIONAL),
        DeckBar("WED", 4800f, targetValue = 5000f),
        DeckBar("THU", 5300f, targetValue = 5000f, valueLabel = "53.00", mark = DeckMarkerType.ESTIMATE),
    )
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            DeckPane(header = "Chart kit", headerAccent = "marker vocabulary + overlay") {
                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("SPARKLINE · LOGGED / ESTIMATE / PROVISIONAL / ENDPOINT", style = LegionType.stamp, color = LocalLegionSemantics.current.faint, modifier = Modifier.padding(horizontal = 12.dp))
                    DeckSparkline(markerSeries, markers = markerTypes, modifier = Modifier.padding(horizontal = 12.dp))
                    DeckSmallMultiple("OIL TEMP", "212F", markerSeries, markers = markerTypes)
                    Text("LINE CHART · TWO-SERIES OVERLAY", style = LegionType.stamp, color = LocalLegionSemantics.current.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    DeckLineChart(
                        series = actual,
                        yLabel = { v -> "%.0f".format(v) },
                        xLabels = (0 until 14).map { if (it % 3 == 0) "D$it" else "" },
                        seriesLabel = "ACTUAL",
                        overlay = DeckLineOverlay(series = budget, label = "BUDGET"),
                    )
                    Text("BAR CHART · DASHED AMBER TARGET, MINT FILL", style = LegionType.stamp, color = LocalLegionSemantics.current.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    DeckBarChart(bars)
                }
            }
        }
    }
}

@Preview(name = "VACUUM/SENTRY · Chart kit (markers + overlay)", showBackground = true, heightDp = 900)
@Composable
private fun PreviewChartKit() {
    LegionTheme { ChartKitDemo() }
}

@Preview(name = "VACUUM/SENTRY · Biometric uplink pane", showBackground = true)
@Composable
private fun PreviewBiometricUplink() {
    LegionTheme { BiometricUplinkDemo() }
}

@Preview(name = "VACUUM/SENTRY · Hero readout", showBackground = true)
@Composable
private fun PreviewHeroReadout() {
    LegionTheme { HeroReadoutDemo() }
}

@Preview(name = "VACUUM/SENTRY · Bezel + section rule + feed rows", showBackground = true)
@Composable
private fun PreviewBezelAndFeed() {
    LegionTheme { BezelAndFeedDemo() }
}

/** Narrow case. The Oppo A17K is roughly 360dp, which is the width that hurts. */
@Preview(name = "VACUUM/SENTRY · 320dp narrow", widthDp = 320, showBackground = true)
@Composable
private fun PreviewNarrow() {
    LegionTheme { BiometricUplinkDemo() }
}

@Preview(name = "VACUUM/SENTRY · Control vocabulary", showBackground = true)
@Composable
private fun PreviewControlVocabulary() {
    LegionTheme { ControlVocabularyDemo() }
}

/** Both of [DeckButton]'s destructive tiers at once - the L11 gate for ticket 04's answer §4. */
@Preview(name = "VACUUM/SENTRY · Destructive button, normal + confirming", showBackground = true)
@Composable
private fun PreviewDestructiveButton() {
    LegionTheme { DestructiveButtonDemo() }
}

@Preview(name = "VACUUM/SENTRY · Dialog", showBackground = true)
@Composable
private fun PreviewDialog() {
    LegionTheme { DialogDemo() }
}
