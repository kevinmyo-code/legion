package com.kevin.legion.ui.controlroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.TypesetType

/**
 * The control room (control room C1-C4), Typeset (ADR 0081): a masthead, four ruled tabs with
 * counts, and one list. Stateless; [ControlRoomScreen] wires the ViewModel and the confirm.
 */
@Composable
fun ControlRoomContent(state: ControlRoomUiState, actions: ControlRoomActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(SoftColors.ground).testTag(ROOT_TAG)) {
        Masthead(actions)
        Tabs(state, actions.onTab)
        state.message?.let {
            val at = gutter.padding(vertical = 8.dp)
            Text(it, style = TypesetType.smallItalic, color = SoftColors.text, modifier = at)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            when (state.tab) {
                ControlTab.SESSIONS -> sessionsTab(state, actions)
                ControlTab.APPROVALS -> approvalsTab(state, actions)
                ControlTab.BOARD -> boardTab(state, actions)
                ControlTab.DECISIONS -> decisionsTab(state, actions)
            }
        }
    }
}

@Composable
private fun Masthead(actions: ControlRoomActions) {
    Row(gutter.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(R.drawable.ms_arrow_back, "Back", actions.onBack)
        Text("Control room", style = TypesetType.lead, color = SoftColors.text, modifier = Modifier.weight(1f))
        IconTap(R.drawable.ms_refresh, "Refresh", actions.onRefresh)
    }
    HorizontalDivider(thickness = 1.dp, color = SoftColors.text)
}

@Composable
private fun IconTap(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { MsIcon(icon, contentDescription = null, tint = SoftColors.text2, size = 22.dp) }
}

@Composable
private fun Tabs(state: ControlRoomUiState, onTab: (ControlTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        ControlTab.entries.forEach { tab ->
            val on = tab == state.tab
            Column(
                Modifier.weight(1f)
                    .clickable(role = Role.Tab, onClick = { onTab(tab) })
                    .semantics { selected = on }
                    .heightIn(min = 48.dp)
                    .padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${tab.label.uppercase()} ${state.count(tab)}",
                    style = TypesetType.caps,
                    color = if (on) SoftColors.text else SoftColors.text2,
                    textAlign = TextAlign.Center,
                )
                val bar = if (on) SoftColors.text else SoftColors.ground
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 2.dp).background(bar))
            }
        }
    }
    HorizontalDivider(thickness = 1.dp, color = SoftColors.barRule)
}

internal val gutter = Modifier.fillMaxWidth().padding(horizontal = 16.dp)

/** A section's small-caps head. */
internal fun LazyListScope.head(text: String) = item {
    Text(
        text.uppercase(),
        style = TypesetType.caps,
        color = SoftColors.text2,
        modifier = gutter.padding(top = 18.dp, bottom = 6.dp),
    )
}

/** Words in place of a list: why it is empty, or why it could not be read. */
internal fun LazyListScope.note(text: String) = item {
    Text(text, style = TypesetType.smallItalic, color = SoftColors.text2, modifier = gutter.padding(vertical = 10.dp))
}

/** A square ink button. [primary] fills it; otherwise it is ruled. */
@Composable
internal fun InkButton(label: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true) {
    val ink = if (enabled) SoftColors.text else SoftColors.text3
    Box(
        Modifier.heightIn(min = 44.dp)
            .then(if (primary) Modifier.background(ink) else Modifier.border(1.dp, ink))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TypesetType.bodyStrong, color = if (primary) SoftColors.ground else ink)
    }
}

/** A row of buttons with a gap. */
@Composable
internal fun Buttons(content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

internal const val ROOT_TAG = "control-room"
