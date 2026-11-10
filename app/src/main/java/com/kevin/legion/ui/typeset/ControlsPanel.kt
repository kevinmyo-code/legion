package com.kevin.legion.ui.typeset

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * What the Controls panel shows (ADR 0081: one panel, six entries). Pure data; the caller reads it
 * from Quiet, the feed blocker, the clock and the Claude watcher.
 */
data class ControlsUi(
    val quietOn: Boolean,
    val feedBlockOn: Boolean,
    /** "6 feed apps blocked", or why the blocker cannot run, in words. */
    val feedNote: String,
    /** True while the ADR 0066 gate (60 s wait, then the sentence) is open under the feed row. */
    val feedGateOpen: Boolean,
    /** "Next alarm 6:30 AM", or "No alarm set". */
    val clockNote: String,
    /** "2 running, 1 needs you", or "None running". */
    val claudeNote: String,
)

/** The panel's taps. Turning the feed blocker ON is one tap; turning it off opens the gate. */
data class ControlsActions(
    val onClose: () -> Unit = {},
    val onToggleQuiet: () -> Unit = {},
    val onFeedBlockOn: () -> Unit = {},
    val onFeedBlockOff: () -> Unit = {},
    val onOpenClock: () -> Unit = {},
    val onOpenClaude: () -> Unit = {},
    val onOpenLegionSettings: () -> Unit = {},
    val onOpenPhoneSettings: () -> Unit = {},
)

/**
 * The Controls panel (`proto-typeset.html` panel): six ruled rows, square, words for every state.
 * Stateless. The feed blocker's OFF path is the ADR 0066 gate unchanged: the caller passes today's
 * `FeedOffGatePanel` as [gate], drawn under the row while [ControlsUi.feedGateOpen]; the switch
 * reads On until the sentence lands. Not yet wired into the shell (Phase C wiring follows Phase B's
 * home surface).
 */
@Composable
fun ControlsPanel(
    ui: ControlsUi,
    actions: ControlsActions,
    modifier: Modifier = Modifier,
    gate: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().background(SoftColors.ground).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Controls", style = TypesetType.lead, color = SoftColors.text, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(48.dp).clickable(role = Role.Button, onClick = actions.onClose)
                    .semantics { contentDescription = "Close controls" },
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(R.drawable.ms_close, contentDescription = null, tint = SoftColors.text2, size = 20.dp)
            }
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
        SwitchRow("Quiet", "Vibrate, calls only", ui.quietOn, actions.onToggleQuiet)
        SwitchRow("Feed blocker", ui.feedNote, ui.feedBlockOn) {
            if (ui.feedBlockOn) actions.onFeedBlockOff() else actions.onFeedBlockOn()
        }
        if (ui.feedGateOpen) gate()
        LinkRow("Clock", ui.clockNote, actions.onOpenClock)
        LinkRow("Claude sessions", ui.claudeNote, actions.onOpenClaude)
        LinkRow("LEGION settings", "Assistant, connections, privacy", actions.onOpenLegionSettings)
        LinkRow("Phone settings", "Wi-Fi, display, battery", actions.onOpenPhoneSettings, external = true)
    }
}

@Composable
private fun SwitchRow(name: String, note: String, on: Boolean, onClick: () -> Unit) {
    val word = if (on) "On" else "Off"
    PanelRow(name, note, Modifier.semantics { stateDescription = word }, onClick) {
        Text(word, style = TypesetType.small, color = SoftColors.text)
        Box(
            Modifier.padding(start = 8.dp).size(width = 36.dp, height = 20.dp).border(1.dp, SoftColors.text),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(3.dp).size(14.dp).background(if (on) SoftColors.text else SoftColors.text2))
        }
    }
}

@Composable
private fun LinkRow(name: String, note: String, onClick: () -> Unit, external: Boolean = false) {
    PanelRow(name, note, Modifier, onClick) {
        // Leaving LEGION for Android's own Settings is said in words, not by a different glyph.
        if (external) Text("Android", style = TypesetType.small, color = SoftColors.text2)
        MsIcon(R.drawable.ms_arrow_forward, contentDescription = null, tint = SoftColors.text2, size = 20.dp)
    }
}

@Composable
private fun PanelRow(
    name: String,
    note: String,
    extra: Modifier,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = name, onClick = onClick).then(extra)) {
        Row(Modifier.padding(vertical = 12.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = TypesetType.bodyStrong, color = SoftColors.text)
                Text(note, style = TypesetType.small, color = SoftColors.text2)
            }
            trailing()
        }
        HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
    }
}
