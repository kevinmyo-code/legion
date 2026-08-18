package com.kevin.legion.ui.companions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.data.local.MemoryEntry
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate
import kotlinx.coroutines.launch

/**
 * `settings/memory` - "what it remembers" (2026-08-18). Two tables, two sections, both
 * driver-editable: [MemoryEntry] ("remembered facts") is the older, still-live table wired to the
 * explicit "remember X" tool/command; [CompanionMemory] ("learned from conversations") is the
 * newer consolidated/reflected memory [com.kevin.legion.ai.MemoryConsolidator] and
 * [com.kevin.legion.ai.ReflectionEngine] distill from a drive's raw transcript. Neither table's
 * own doc comment claims the other is obsolete - see [CompanionMemory]'s "unifying the two recall
 * paths is a later ticket's call, not this one's" - so this screen shows both rather than picking
 * a side.
 *
 * **Why this screen exists at all.** Nothing upstream of it ever asks the driver whether a
 * consolidated memory is actually right - [com.kevin.legion.ai.ReflectionEngine] synthesizes new
 * rows from clusters of old ones with no human in the loop. A wrong memory a driver cannot see or
 * remove would sit there being read into every future prompt indefinitely. This is that visibility
 * and that removal, nothing more - it does not edit a memory's text, only deletes it outright.
 *
 * **Read is [CompanionMemoryDao.allRecent], not [CompanionMemoryDao.getRecent].** The latter is
 * scoped to one `vehicleId`, which is right for [com.kevin.legion.ai.AriaBrain]'s own recall (which
 * always knows which car it's talking about) and wrong here - see [allRecent]'s own doc comment for
 * why a driver-facing "find and delete a wrong memory" screen must not hide a row attached to a car
 * he isn't currently sitting in.
 *
 * **Delete is immediate, no confirm dialog, no [android.app.AlertDialog]/system modal** - a single
 * row is not the ledger-purge shape ([com.kevin.legion.ui.PurgeLedgerRow]'s two-tap arm), it is one
 * fact leaving one list, and [MemoryDao]/[CompanionMemoryDao] have no undo to make a confirm step
 * meaningfully safer than just doing it.
 */
private const val MEMORY_LIMIT = 200

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var facts by remember { mutableStateOf(emptyList<MemoryEntry>()) }
    var learned by remember { mutableStateOf(emptyList<CompanionMemory>()) }

    LaunchedEffect(Unit) {
        val db = CarDatabase.getDatabase(context)
        // Room's suspend DAO methods already dispatch off the caller's thread through their own
        // query executor - unlike PlaybookStore's raw file IO, no explicit Dispatchers.IO wrap is
        // needed here (same posture ai/AriaBrain.kt's own memoryDao/companionMemoryDao calls take).
        facts = db.memoryDao().getRecent(MEMORY_LIMIT)
        learned = db.companionMemoryDao().allRecent(MEMORY_LIMIT)
        loading = false
    }

    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "What it remembers", onBack = onBack)
            if (loading) {
                Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "facts-header") { SectionHeader("REMEMBERED FACTS", facts.size.toString()) }
                    if (facts.isEmpty()) {
                        // Never a bare header with nothing under it - an empty section still says
                        // so in words, the same "state plainly what is not set" posture CLAUDE.md
                        // §7 applies to credential rows applied here to memory rows.
                        item(key = "facts-empty") { EmptyMemorySection("Nothing remembered yet.") }
                    } else {
                        items(facts, key = { "fact-${it.id}" }) { entry ->
                            Column {
                                MemoryFactRow(
                                    entry = entry,
                                    onDelete = {
                                        scope.launch {
                                            CarDatabase.getDatabase(context).memoryDao().deleteById(entry.id)
                                            facts = facts.filterNot { it.id == entry.id }
                                        }
                                    },
                                )
                                Hairline()
                            }
                        }
                    }

                    item(key = "learned-header") { SectionHeader("LEARNED FROM CONVERSATIONS", learned.size.toString()) }
                    if (learned.isEmpty()) {
                        item(key = "learned-empty") { EmptyMemorySection("Nothing learned yet.") }
                    } else {
                        items(learned, key = { "learned-${it.id}" }) { memory ->
                            Column {
                                CompanionMemoryRow(
                                    memory = memory,
                                    onDelete = {
                                        scope.launch {
                                            CarDatabase.getDatabase(context).companionMemoryDao().deleteById(memory.id)
                                            learned = learned.filterNot { it.id == memory.id }
                                        }
                                    },
                                )
                                Hairline()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One explicitly-remembered fact: its text, when it was set (or last touched), and a DELETE
 * affordance - no confirm, see the file doc comment for why. */
@Composable
private fun MemoryFactRow(entry: MemoryEntry, onDelete: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entry.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(shortDate(entry.timestamp), style = LegionType.stamp, color = sem.faint)
        }
        TextButton(onClick = onDelete) {
            Text("DELETE", style = LegionType.stamp, color = sem.estimated)
        }
    }
}

/** One consolidated/reflected memory: its text, then [CompanionMemory.category]/[CompanionMemory
 * .source]/[CompanionMemory.importance]/[CompanionMemory.createdAt] on the caption line - the four
 * fields the task calls out, in the same order the class itself declares them. */
@Composable
private fun CompanionMemoryRow(memory: CompanionMemory, onDelete: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(memory.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${memory.category} - ${memory.source} - importance ${memory.importance} - ${shortDate(memory.createdAt)}",
                style = LegionType.stamp,
                color = sem.faint,
            )
        }
        TextButton(onClick = onDelete) {
            Text("DELETE", style = LegionType.stamp, color = sem.estimated)
        }
    }
}

@Composable
private fun EmptyMemorySection(text: String) {
    val sem = LocalLegionSemantics.current
    Text(text, style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
}
