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

/** The row's copy, kept here so a test can hold it to "never name the assistant". */
internal object PhoneLogCopy {
    const val HEADER = "DEVELOPER"
    const val TITLE = "Send this phone's logs to the engine"
    const val DETAIL = "Everything the app writes to its log, crashes included, so a problem can be read " +
        "on the engine without a cable. Passwords, keys, email addresses, phone numbers and what your " +
        "mail says are removed on this phone first. At most 4 MB a day; kept 30 days on the engine."
    const val OFF_DETAIL = "Nothing is captured or sent, and what this phone was holding is deleted."
    const val SEND_NOW = "Send now"
    const val SENDING = "Sending..."
}

/**
 * The phone's own log capture (2026-11-09, `service/phonelog/PhoneLog`): one switch, default on,
 * and "Send now". Stateless; the screen owns the state and the calls.
 */
@Composable
internal fun PhoneLogRow(
    enabled: Boolean,
    sending: Boolean,
    lastResult: String?,
    onToggle: (Boolean) -> Unit,
    onSendNow: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Text(PhoneLogCopy.HEADER, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        PhoneLogCopy.TITLE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        (if (enabled) "On - " else "Off - ") +
                            if (enabled) PhoneLogCopy.DETAIL else PhoneLogCopy.OFF_DETAIL,
                        style = LegionType.stamp,
                        color = sem.faint,
                    )
                }
                DeckSwitch(checked = enabled, onCheckedChange = onToggle)
            }
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
                    text = if (sending) PhoneLogCopy.SENDING else PhoneLogCopy.SEND_NOW,
                    onClick = onSendNow,
                    enabled = enabled && !sending,
                )
            }
        }
    }
}
