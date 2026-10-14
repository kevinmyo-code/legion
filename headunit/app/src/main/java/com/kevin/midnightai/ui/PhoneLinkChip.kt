package com.kevin.midnightai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.kevin.midnightai.bluetooth.LinkState
import com.kevin.midnightai.bluetooth.PhoneLinkManager
import com.kevin.midnightai.ui.theme.AriaColors
import com.kevin.midnightai.ui.theme.AriaDimens
import com.kevin.midnightai.ui.theme.AriaType

/**
 * Small home-screen indicator of which phone is currently carrying audio - the thing Kevin and
 * his wife had no way to check at a glance before this feature. Reads [PhoneLinkManager.state]
 * directly; does not call [PhoneLinkManager.init] itself (the manager screen or the service does
 * that once per process - this chip just observes).
 *
 * Wired into Cruise/ControlPanel by the orchestrator, not here (this file only defines the
 * composable per the brief's file boundary).
 */
@Composable
fun PhoneLinkChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val state by PhoneLinkManager.state.collectAsState()
    val active = state.phones.firstOrNull { it.address == state.activeAddress }
    val label = active?.name?.let { it.take(MAX_NAME_CHARS) } ?: "NO PHONE"
    val dotColor = if (active?.audioState == LinkState.CONNECTED) AriaColors.NeonCyan else AriaColors.TextDim

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(AriaColors.Panel, RoundedCornerShape(AriaDimens.pageCorner))
            .clickable(onClick = onClick)
            .padding(horizontal = AriaDimens.s2, vertical = AriaDimens.s1),
    ) {
        Text("●", color = dotColor, fontSize = 12.sp)
        Text(
            "  $label",
            style = AriaType.readout.copy(fontSize = 12.sp),
            color = AriaColors.TextPrimary,
        )
    }
}

private const val MAX_NAME_CHARS = 14
