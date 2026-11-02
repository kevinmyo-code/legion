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
import com.kevin.legion.dumb.FeedOffRule
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * "Block feed apps always" (ADR 0066): the blocked apps are blocked outside Matrix mode too, with a
 * 15 minute pass as the way in. Off by default; stored live, so the bounce sees a flip at once.
 *
 * Shown in two places on one preference (Kevin, 2026-10-30, after he could not find it inside
 * Matrix mode): its own "Feed blocker" row at the top level of Settings, and the Matrix mode screen.
 * Each composition reads the store fresh, so the two never disagree.
 *
 * Turning it ON is instant. Turning it OFF costs the same as a pass (Kevin, 2026-11-02): the switch
 * stays on through a 60 second wall-clock wait and a typed sentence ([FeedOffGatePanel]), then goes off.
 */
@Composable
internal fun FeedBlockRow(accessibilityOn: Boolean) {
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    val graph = remember(context) { (context.applicationContext as MidnightApplication).dumb }
    val store = graph.feedBlock
    val gate = graph.feedOffGate
    var on by remember { mutableStateOf(store.get()) }
    var askingOff by remember { mutableStateOf(gate.waitRemainingMs(FeedOffRule.SWITCH) != null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        on = store.get()
        askingOff = on && gate.waitRemainingMs(FeedOffRule.SWITCH) != null
        graph.feedBlockWatch.check()
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Block feed apps always", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        feedBlockWords(on, accessibilityOn),
                        style = LegionType.stamp,
                        color = if (on && !accessibilityOn) sem.estimated else sem.faint,
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = { turnOn ->
                        if (turnOn) {
                            store.set(true)
                            on = true
                            gate.cancel(FeedOffRule.SWITCH)
                            askingOff = false
                            graph.feedBlockWatch.check()
                        } else {
                            gate.request(FeedOffRule.SWITCH)
                            askingOff = true
                        }
                    },
                )
            }
        }
        if (on && askingOff) {
            FeedOffGatePanel(gate, FeedOffRule.SWITCH, FeedOffRule.SWITCH_SENTENCE, "Turn off", onConfirmed = {
                store.set(false)
                on = false
                askingOff = false
                graph.feedBlockWatch.check()
            }, onDropped = { askingOff = false })
        }
    }
}

private fun feedBlockWords(on: Boolean, accessibilityOn: Boolean): String = when {
    !on -> "Off. Blocked apps are blocked only in Matrix mode."
    accessibilityOn ->
        "On. Blocked apps are blocked outside Matrix mode too. A 15 min pass opens one " +
            "after a 60 second wait and a sentence; turning this off takes the same."
    else -> "On, but the accessibility service (LEGION blocker) is off, so nothing is sent home yet."
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
