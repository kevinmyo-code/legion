package com.kevin.legion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * "Conversation log" in Assistant settings (conversation-records ticket 03): two switches and a
 * "Send now" action. Stateless; the screen owns the state and the calls.
 */
@Composable
internal fun ConversationLogRow(
    keepAndSend: Boolean,
    logcatMirror: Boolean,
    sending: Boolean,
    lastResult: String?,
    onKeepAndSend: (Boolean) -> Unit,
    onLogcatMirror: (Boolean) -> Unit,
    onSendNow: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                ConversationLogCopy.TITLE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(ConversationLogCopy.SUBTITLE, style = LegionType.stamp, color = sem.faint)
            Spacer(Modifier.height(8.dp))
            SwitchLine(ConversationLogCopy.KEEP, ConversationLogCopy.KEEP_DETAIL, keepAndSend, onKeepAndSend)
            Spacer(Modifier.height(4.dp))
            SwitchLine(ConversationLogCopy.MIRROR, ConversationLogCopy.MIRROR_DETAIL, logcatMirror, onLogcatMirror)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    lastResult.orEmpty(),
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                DeckButton(
                    text = if (sending) ConversationLogCopy.SENDING else ConversationLogCopy.SEND_NOW,
                    onClick = onSendNow,
                    enabled = keepAndSend && !sending,
                )
            }
        }
    }
}

@Composable
private fun SwitchLine(title: String, detail: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            Text((if (checked) "On - " else "Off - ") + detail, style = LegionType.stamp, color = sem.faint)
        }
        DeckSwitch(checked = checked, onCheckedChange = onToggle)
    }
}
