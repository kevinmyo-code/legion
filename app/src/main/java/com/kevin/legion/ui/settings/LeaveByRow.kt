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
import com.kevin.legion.leaveby.LeaveBy
import com.kevin.legion.leaveby.PrefsLeaveByStore
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * The hands path for "stop telling me when to leave" (ADR 0035, daily-loops ticket 05): the one
 * switch for leave-by times, the same stored setting the notification's STOP LEAVE-BY action flips.
 * Off means no estimate is asked for and nothing is raised. Sits under the Timing row it lives
 * inside: with Timing off the line is not spoken or posted, and the row says so in words.
 */
@Composable
fun LeaveByRow(timingOn: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(true) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = PrefsLeaveByStore(context).enabled()
    }

    val sem = LocalLegionSemantics.current
    Spacer(Modifier.height(4.dp))
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Leave-by times",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "For a calendar event with a place: when to leave, from where the phone is, with " +
                        "traffic (an estimate; needs a Mapbox token and a location fix). A notification " +
                        "and Alfred say it once per event." +
                        if (timingOn) "" else " Silent while Timing is off.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            DeckSwitch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    scope.launch { LeaveBy.controller(context).setEnabled(on) }
                },
            )
        }
    }
}
