package com.kevin.legion.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The header's Claude sessions button, immediately left of the settings gear (Kevin 2026-11-04).
 * [running] is the count of running tasks from the last engine read ([com.kevin.legion.claude.ClaudeLiveWatch]);
 * above zero it shows as a small badge and is also said in words ("2 running") to TalkBack, so the
 * count is never a colour or a dot alone. Laid out at 48dp, the full touch target.
 */
@Composable
fun ClaudeHeaderButton(running: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .testTag(CLAUDE_BUTTON_TAG)
            .semantics { stateDescription = if (running > 0) "$running running" else "nothing running" },
    ) {
        BadgedBox(
            badge = {
                if (running > 0) {
                    Badge(containerColor = SoftColors.primary, contentColor = SoftColors.ground) {
                        Text(if (running > MAX_SHOWN) "$MAX_SHOWN+" else running.toString())
                    }
                }
            },
        ) {
            MsIcon(res = R.drawable.ms_terminal, contentDescription = "Claude sessions", tint = SoftColors.text2)
        }
    }
}

private const val MAX_SHOWN = 9

const val CLAUDE_BUTTON_TAG = "header-claude-sessions"
