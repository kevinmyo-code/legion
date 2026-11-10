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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.spokenbrief.SpokenBrief
import com.kevin.legion.spokenbrief.SpokenBriefs
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * The hands path for "stop the morning brief" and "stop the home brief" (ADR 0035, ADR 0082): one
 * switch per spoken brief, the same stored switch the voice instruction flips
 * ([SpokenBriefs.set]). Sits under the Digest category row, which it lives inside: with Digest off
 * neither brief speaks, and the row says so in words. Self-loading, so the screen that hosts it
 * adds one line.
 */
@Composable
fun SpokenBriefRows(digestOn: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf<Map<SpokenBrief, Boolean>>(emptyMap()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { on = SpokenBrief.entries.associateWith { SpokenBriefs.isOn(context, it) } }
    }

    SpokenBrief.entries.forEach { brief ->
        Spacer(Modifier.height(4.dp))
        SpokenBriefRow(
            brief = brief,
            enabled = on[brief] ?: true,
            digestOn = digestOn,
            onToggle = { value ->
                on = on + (brief to value)
                scope.launch { SpokenBriefs.set(context, brief, value) }
            },
        )
    }
}

@Composable
private fun SpokenBriefRow(brief: SpokenBrief, enabled: Boolean, digestOn: Boolean, onToggle: (Boolean) -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    brief.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    brief.blurb + if (digestOn) "" else " Silent while Digest is off.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            DeckSwitch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}
