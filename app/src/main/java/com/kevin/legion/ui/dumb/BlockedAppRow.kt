package com.kevin.legion.ui.dumb

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kevin.legion.MidnightApplication
import com.kevin.legion.dumb.BlockPolicy
import com.kevin.legion.dumb.BlockReason
import com.kevin.legion.dumb.FeedOffGate
import com.kevin.legion.dumb.FeedOffRule
import com.kevin.legion.ui.apps.AppDrawerCache
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One row of the Blocked apps list: what the app is, its state and why, all as words. */
internal data class BlockedAppRow(val label: String, val packageName: String, val blocked: Boolean, val why: String) {
    val state: String get() = if (blocked) "Blocked" else "Open"
}

/**
 * Every launchable app with its Matrix mode state and the reason for it (ADR 0062, 2026-10-24).
 * Tapping a row flips it. Blocked apps come first so the list answers "what is blocked?" at a glance;
 * state is spelled out in words, never a colour alone. LEGION, Settings and the Home app say they are
 * always open and ignore the tap.
 *
 * Blocking is instant. Unblocking costs the same as a pass (Kevin, 2026-11-02): the tap starts a 60
 * second wall-clock wait, then a typed sentence ([FeedOffGatePanel]) under the row, then the flip.
 */
@Composable
internal fun BlockedAppsSection() {
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    val graph = remember(context) { (context.applicationContext as MidnightApplication).dumb }
    val policy = graph.blockPolicy
    val gate = graph.feedOffGate
    // Bumped on a flip so the rows are rebuilt from the stored choice.
    var version by remember { mutableIntStateOf(0) }
    val rows by produceState<List<BlockedAppRow>?>(initialValue = null, version) {
        value = withContext(Dispatchers.IO) { buildRows(context, policy) }
    }
    // Unblock requests still waiting, re-read from the persisted instants on every tap (the rows may
    // compare equal after a request, so [version] is a key too).
    val asking by produceState(initialValue = emptySet<String>(), rows, version) {
        val list = rows.orEmpty()
        value = withContext(Dispatchers.IO) {
            list.filter { gate.waitRemainingMs(it.packageName) != null }.map { it.packageName }.toSet()
        }
    }
    var flipped by remember { mutableStateOf<String?>(null) }

    Text("Blocked apps", style = MaterialTheme.typography.labelLarge)
    Text(
        "Social, video and news apps are blocked, plus a list of known feed apps. Anything that opens links, " +
            "mail or messages is not blocked by tag. Tap an app to flip it.",
        style = LegionType.stamp,
        color = sem.faint,
    )
    flipped?.let { Text(it, style = LegionType.stamp, color = sem.faint) }
    val list = rows
    if (list == null) {
        Text("Reading the installed apps...", style = LegionType.stamp, color = sem.faint)
        return
    }
    if (list.isEmpty()) {
        Text(
            "No launchable apps were found, so there is nothing to list.",
            style = LegionType.stamp,
            color = sem.estimated,
        )
        return
    }
    list.forEach { row ->
        BlockedAppItem(row) {
            flipped = tapWords(policy, gate, row)
            version++
        }
        if (row.packageName in asking) {
            UnblockGate(row, policy, gate) { words ->
                words?.let { flipped = it }
                version++
            }
        }
    }
}

@Composable
private fun BlockedAppItem(row: BlockedAppRow, onTap: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Surface(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = if (row.blocked) "Unblock" else "Block", onClick = onTap),
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(row.label, style = MaterialTheme.typography.bodyMedium)
                Text(row.why, style = LegionType.stamp, color = sem.faint)
            }
            Text(row.state, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The wait and sentence under a row whose unblock is pending. [onDone] gets what changed, or null if nothing. */
@Composable
private fun UnblockGate(row: BlockedAppRow, policy: BlockPolicy, gate: FeedOffGate, onDone: (String?) -> Unit) {
    FeedOffGatePanel(
        gate = gate,
        target = row.packageName,
        sentence = FeedOffRule.unblockSentence(row.label),
        confirmLabel = "Unblock ${row.label}",
        onConfirmed = {
            // Only a still-blocked app flips: a change made elsewhere meanwhile must not re-block it.
            onDone(
                if (policy.verdictWithoutPass(row.packageName).blocked) {
                    flipWords(policy, row)
                } else {
                    "${row.label} is already open."
                },
            )
        },
        onDropped = { onDone(null) },
    )
}

/**
 * A tap on a row: blocking flips at once; unblocking starts the wait instead (Kevin, 2026-11-02). The
 * direction is read without the pass, since an app on a pass is still blocked underneath.
 */
private fun tapWords(policy: BlockPolicy, gate: FeedOffGate, row: BlockedAppRow): String {
    if (!FeedOffRule.flipNeedsGate(policy.verdictWithoutPass(row.packageName).blocked)) return flipWords(policy, row)
    gate.request(row.packageName)
    return "${row.label} stays blocked for now: unblocking takes a 60 second wait and a sentence."
}

/** Flips [row]'s app and says what changed, in words. A running pass is named: it runs out first. */
private fun flipWords(policy: BlockPolicy, row: BlockedAppRow): String {
    val after = policy.flip(row.packageName)
    if (after.reason == BlockReason.SYSTEM) return "${row.label} cannot be blocked (${after.reason.words})."
    val live = policy.verdict(row.packageName)
    val passNote = if (live.reason == BlockReason.PASS) " Its pass runs first (${live.words})." else ""
    return "${row.label} is now ${if (after.blocked) "blocked" else "open"}: ${after.words}.$passNote"
}

private suspend fun buildRows(context: android.content.Context, policy: BlockPolicy): List<BlockedAppRow> {
    val loaded = AppDrawerCache.peek() ?: runCatching { AppDrawerCache.refresh(context) }.getOrNull()
    val apps = loaded?.apps.orEmpty().distinctBy { it.packageName }
    return apps.map {
        val v = policy.verdict(it.packageName)
        BlockedAppRow(it.label, it.packageName, v.blocked, v.words)
    }.sortedWith(compareByDescending<BlockedAppRow> { it.blocked }.thenBy { it.label.lowercase() })
}
