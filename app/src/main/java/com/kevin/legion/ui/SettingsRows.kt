package com.kevin.legion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckRadio
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.TempUnit

/**
 * Plain UI half of [SettingsScreen] (the state-holder/UI split,
 * `.claude/skills/compose-state-holder-ui-split`, same shape as
 * `ui/sync/DriveSyncRows.kt` and `ui/spotify/SpotifyRows.kt`). Everything here
 * is display plus callbacks - no `Context`, no permission launcher, no
 * [com.kevin.legion.service.AssistantIgnition] - so every state previews.
 *
 * **Each row states its own current status in words**, not just its name. The
 * old screen was four bare M3 `Button`s with nothing but a label, so the only
 * way to find out whether the Gemini key was set, Drive was connected, or
 * Spotify was half-configured was to open each screen in turn and look. That
 * matters more here than on most screens: this menu is the entry point for
 * every credential the app holds, and CLAUDE.md §7's worded-state rule applies
 * to "is this configured" exactly as it does to ingestion provenance.
 */

/**
 * One tappable settings destination: what it is, what state it is in right
 * now, and a chevron. [status] is the worded state ("Not set", "Connected");
 * [attention] draws it in [LegionSemantics.estimated] for a state that
 * genuinely blocks something, rather than the ordinary faint - ADVISORY per
 * ticket 04's tiers (mission-control ticket 13 re-home: "not configured" was
 * never a failed gate or an active fault), not [LegionSemantics.quarantined].
 */
@Composable
fun SettingsNavRow(
    label: String,
    status: String,
    onClick: () -> Unit,
    attention: Boolean = false,
) {
    val sem = LocalLegionSemantics.current
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    status,
                    style = LegionType.stamp,
                    color = if (attention) sem.estimated else sem.faint,
                )
            }
            Text(">", style = LegionType.stamp, color = sem.faint)
        }
    }
}

/**
 * The assistant ignition toggle. Unchanged in substance from the version that
 * lived inside [SettingsScreen] - the permission chain still belongs to the
 * state holder; this only draws the switch and the refusal line.
 */
@Composable
fun IgnitionRow(
    enabled: Boolean,
    refusalReason: String?,
    onToggle: (Boolean) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Assistant", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (enabled) "On - tap to talk strip is showing" else "Off",
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (refusalReason != null) {
                // ADVISORY (ticket 13 re-home): a blocked capability, not a failed gate.
                Text(refusalReason, style = LegionType.stamp, color = sem.estimated)
            }
        }
    }
}

/**
 * Whether Zero proactively mentions open NHTSA recalls once at startup
 * ([com.kevin.legion.service.AriaForegroundService.checkRecallsOnce]). Off by default - a network
 * call made on the driver's behalf every launch, not on request - and, since mission-control
 * ticket 12 (`.scratch/fleet-maintenance/issues/12-a-recall-button.md`), gated the same
 * identity-present way as the on-request check under Fleet -> Specs and the `check_recalls` voice
 * tool. Ticket 12's finding: [com.kevin.legion.service.DebugSettings.setRecallAlerts] had zero
 * callers before this row - a preference nobody could change, gating a proactive nobody had seen.
 */
@Composable
fun RecallAlertsRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Recall alerts", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (enabled) {
                        "On - Zero mentions it once at startup if NHTSA lists any open recall"
                    } else {
                        "Off - check any time under Fleet -> Specs"
                    },
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/**
 * The proactive master switch ([com.kevin.legion.service.ProactivePreferences.muted], inverted for
 * display - the row reads "Proactive speech", not "Muted"). `.scratch/proactive-mode/issues/
 * 01-one-gate-not-three.md` (2026-08-18): `setMuted`/`toggle` had zero callers anywhere before this
 * row, so the kill switch this whole effort depends on had never been reachable by a human. Uses
 * [DeckSwitch] rather than [RecallAlertsRow]/[IgnitionRow]'s raw Material `Switch` - those predate
 * ticket 09's deck control set; this is new work and adopts it directly rather than copying the
 * pattern this map is here to move away from.
 *
 * **A true kill switch, stated as one** (settled decision 2, `.scratch/proactive-mode/map.md`):
 * flipping this off does not just quiet nudges. It silences every line Alfred would otherwise say
 * unprompted - openers, alerts, reminders, and the incoming-call announcement alike, all now routed
 * through [com.kevin.legion.service.ProactiveBus.speakIfAllowed] - and separately stops
 * [com.kevin.legion.service.AmbientListener] from listening at all, not merely from reacting
 * (Kevin's explicit requirement, unchanged by this ticket). The status line says both, in words,
 * rather than leaving either as an implied consequence of "muted".
 */
@Composable
fun ProactiveSpeechRow(proactiveOn: Boolean, onToggle: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Proactive speech", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        if (proactiveOn) {
                            "On - Alfred may speak first: openers, alerts, reminders, incoming calls"
                        } else {
                            "Off - stops every unprompted line, including incoming-call announcements, " +
                                "and stops ambient listening too. Talking to Alfred yourself still works."
                        },
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                DeckSwitch(checked = proactiveOn, onCheckedChange = onToggle)
            }
        }
    }
}

/**
 * The driver's chosen temperature unit ([com.kevin.legion.util.Temp]), a two-way choice with no
 * destination screen of its own - ticket 07, amended 2026-08-18 to make the unit a setting rather
 * than fixed Celsius. Uses [DeckRadio] rather than a Material `RadioButton`/`Switch` pair,
 * matching the deck control set the other new mission-control rows on this screen already prefer
 * over raw Material controls.
 */
@Composable
fun TemperatureUnitRow(unit: TempUnit, onSelect: (TempUnit) -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Temperature unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Coolant and intake-air readings, everywhere they're shown or spoken.",
                style = LegionType.stamp,
                color = sem.faint,
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                DeckRadio(selected = unit == TempUnit.CELSIUS, onClick = { onSelect(TempUnit.CELSIUS) }, label = TempUnit.CELSIUS.spokenWord)
                DeckRadio(selected = unit == TempUnit.FAHRENHEIT, onClick = { onSelect(TempUnit.FAHRENHEIT) }, label = TempUnit.FAHRENHEIT.spokenWord)
            }
        }
    }
}

/**
 * The "who is active" line. [name] null means the roster hasn't loaded yet or
 * (a genuinely fresh install, pre-onboarding) no profile is active on this
 * device at all; both render as "No companion set up yet" rather than blank
 * space, matching CLAUDE.md's say-plainly-what-is-not-set posture.
 */
@Composable
fun ActiveCompanionRow(name: String?, blurb: String?, onOpenCompanions: () -> Unit) {
    SettingsNavRow(
        label = "Companion",
        status = if (name != null) listOfNotNull(name, blurb).joinToString(" - ") else "No companion set up yet",
        onClick = onOpenCompanions,
    )
}

/**
 * The ledger purge, re-homed from CRED's own root (mission-control ticket 16, ticket 12's ruling:
 * "a destructive purge does not belong on a surface you open daily"). Content and confirm shape
 * are UNCHANGED from the old `ui.LedgerScreen.PurgeLedgerRow` - two taps, never one, the second one
 * saying exactly what it destroys, `armed` resetting on a fresh `remember` so leaving Setup and
 * coming back lands on the safe state - only the CONTROL migrated, from a bare [TextButton] to
 * [DeckButton] (ticket 16's binding: "migrate any control you touch to DeckControls"), which is
 * also what lets this row carry ticket 04 answer §4's real destructive treatment for the first
 * time: neutral `ink` outline every day (`destructive = true, confirming = false`), full `chrome`
 * fill spent only on the confirming second tap (`confirming = true`) - the bare [TextButton] this
 * replaces had no such distinction to begin with.
 */
@Composable
fun PurgeLedgerRow(onPurge: () -> Unit) {
    val sem = LocalLegionSemantics.current
    var armed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text("Ledger", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            if (armed) {
                "This deletes every imported transaction and forgets every file already scanned. " +
                    "Fleet and pantry data are not touched. This cannot be undone."
            } else {
                "Delete every imported transaction and rescan the folder from scratch."
            },
            style = LegionType.stamp,
            color = if (armed) sem.chrome else sem.faint,
        )
        Spacer(Modifier.height(6.dp))
        // `Modifier.fillMaxWidth()` on the Row plus `Modifier.weight(1f)` on each armed-state
        // button is load-bearing, not decoration - caught on-device (mission-control ticket 16):
        // without it, a bare `Row` gives each child an effectively unbounded measuring pass, "YES,
        // PURGE THE LEDGER" claims almost the entire row on its own natural width, and CANCEL is
        // left so little room its own Text wraps one glyph per line. Even width split, never
        // wrap-per-glyph. The everyday (unarmed) single-button state is left un-weighted so it
        // keeps its old compact, content-sized box rather than stretching full width for no reason.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DeckButton(
                text = if (armed) "YES, PURGE THE LEDGER" else "PURGE LEDGER",
                onClick = { if (armed) onPurge() else armed = true },
                destructive = true,
                confirming = armed,
                modifier = if (armed) Modifier.weight(1f) else Modifier,
            )
            if (armed) {
                DeckButton(text = "CANCEL", onClick = { armed = false }, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Settings row: key not set", widthDp = 360)
@Composable
private fun PreviewRowAttention() = LegionTheme {
    Surface { SettingsNavRow(label = "Gemini key", status = "Not set", onClick = {}, attention = true) }
}

@Preview(name = "Settings row: ordinary", widthDp = 360)
@Composable
private fun PreviewRowOrdinary() = LegionTheme {
    Surface { SettingsNavRow(label = "Google", status = "Drive connected", onClick = {}) }
}

@Preview(name = "Settings: ignition off", widthDp = 360)
@Composable
private fun PreviewIgnitionOff() = LegionTheme {
    Surface { IgnitionRow(enabled = false, refusalReason = null, onToggle = {}) }
}

@Preview(name = "Settings: ignition on", widthDp = 360)
@Composable
private fun PreviewIgnitionOn() = LegionTheme {
    Surface { IgnitionRow(enabled = true, refusalReason = null, onToggle = {}) }
}

@Preview(name = "Settings: ignition refused", widthDp = 360)
@Composable
private fun PreviewIgnitionRefused() = LegionTheme {
    Surface {
        IgnitionRow(
            enabled = false,
            refusalReason = "Microphone permission was refused. The assistant needs it to hear " +
                "you - ledger, pantry, and fleet are unaffected.",
            onToggle = {},
        )
    }
}

@Preview(name = "Settings: no companion yet", widthDp = 360)
@Composable
private fun PreviewCompanionNone() = LegionTheme {
    Surface { ActiveCompanionRow(name = null, blurb = null, onOpenCompanions = {}) }
}

@Preview(name = "Settings: companion active", widthDp = 360)
@Composable
private fun PreviewCompanionActive() = LegionTheme {
    Surface { ActiveCompanionRow(name = "Aria", blurb = "dry, competent, unbothered", onOpenCompanions = {}) }
}

@Preview(name = "Settings: purge ledger, everyday state", widthDp = 360)
@Composable
private fun PreviewPurgeLedgerRowNeutral() = LegionTheme {
    Surface { PurgeLedgerRow(onPurge = {}) }
}

@Preview(name = "Settings: recall alerts off", widthDp = 360)
@Composable
private fun PreviewRecallAlertsOff() = LegionTheme {
    Surface { RecallAlertsRow(enabled = false, onToggle = {}) }
}

@Preview(name = "Settings: recall alerts on", widthDp = 360)
@Composable
private fun PreviewRecallAlertsOn() = LegionTheme {
    Surface { RecallAlertsRow(enabled = true, onToggle = {}) }
}

@Preview(name = "Settings: proactive speech on", widthDp = 360)
@Composable
private fun PreviewProactiveSpeechOn() = LegionTheme {
    Surface { ProactiveSpeechRow(proactiveOn = true, onToggle = {}) }
}

@Preview(name = "Settings: proactive speech off", widthDp = 360)
@Composable
private fun PreviewProactiveSpeechOff() = LegionTheme {
    Surface { ProactiveSpeechRow(proactiveOn = false, onToggle = {}) }
}
