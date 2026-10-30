package com.kevin.legion.ui.dumb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.MidnightApplication
import com.kevin.legion.dumb.DumbSetup
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * "Block feed apps always" (ADR 0066): the blocked apps are blocked outside Matrix mode too, with a
 * 15 minute pass as the way in. Off by default; stored live, so the bounce sees a flip at once.
 *
 * Shown in two places on one preference (Kevin, 2026-10-30, after he could not find it inside
 * Matrix mode): its own "Feed blocker" row at the top level of Settings, and the Matrix mode screen.
 * Each composition reads the store fresh, so the two never disagree.
 */
@Composable
internal fun FeedBlockRow(accessibilityOn: Boolean) {
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    val store = remember(context) { (context.applicationContext as MidnightApplication).dumb.feedBlock }
    var on by remember { mutableStateOf(store.get()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { on = store.get() }
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Block feed apps always", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        !on -> "Off. Blocked apps are blocked only in Matrix mode."
                        accessibilityOn ->
                            "On. Blocked apps are blocked outside Matrix mode too. A 15 min pass opens one " +
                                "after a 60 second wait and a sentence."
                        else -> "On, but the accessibility service (LEGION blocker) is off, so nothing is " +
                            "sent home yet."
                    },
                    style = LegionType.stamp,
                    color = if (on && !accessibilityOn) sem.estimated else sem.faint,
                )
            }
            Switch(
                checked = on,
                onCheckedChange = {
                    store.set(it)
                    on = it
                },
            )
        }
    }
}

/** The top-level Settings row: [FeedBlockRow] with the accessibility fact read on each resume. */
@Composable
fun FeedBlockerSettingsRow() {
    val context = LocalContext.current
    var accessibilityOn by remember { mutableStateOf(DumbSetup.factsFor(context).accessibilityEnabled) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityOn = DumbSetup.factsFor(context).accessibilityEnabled
    }
    FeedBlockRow(accessibilityOn = accessibilityOn)
}
