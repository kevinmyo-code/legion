package com.kevin.legion.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.GroceryItem
import com.kevin.legion.grocery.GroceryController
import com.kevin.legion.grocery.GroceryRowView
import com.kevin.legion.grocery.TripSummary
import com.kevin.legion.grocery.buildGroceryRows
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * The grocery trip, as a mode inside the LOG tab (Kevin, 2026-08-11: "a grocery list, made once and
 * torn down once grocery is complete... can tag onto existing LOG tab").
 *
 * A full-screen mode rather than a pane above the item stream, by Kevin's call: this is read
 * one-handed walking round a shop, where the whole screen wants to be the thing being shopped.
 * `ui/NotesScreen.kt` owns the ITEMS | GROCERY toggle that swaps between them.
 *
 * Two things this screen has to say OUT LOUD rather than imply, both because they destroy data:
 * - **DONE deletes the whole list.** It is confirmed, always, and the confirmation states how many
 *   items were never ticked, because those are about to be thrown away too.
 * - **Only ticked items are remembered.** The staples memory learns from what went in the basket,
 *   never from what merely got typed - so the confirmation says what will be remembered.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill, same shape as [InboxScreen]:
 * [GroceryScreen] owns the loads and every write, [GroceryContent] is plain state plus callbacks
 * and is what the `@Preview`s below exercise.
 */
data class GroceryUiState(
    val loading: Boolean = true,
    val rows: List<GroceryRowView> = emptyList(),
    /** Staples to offer as one-tap adds, already filtered of anything on the list. */
    val suggestions: List<String> = emptyList(),
) {
    val remaining: Int get() = rows.count { !it.done }
    val tripInProgress: Boolean get() = rows.isNotEmpty()
}

@Composable
fun GroceryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(GroceryUiState()) }
    var rawItems by remember { mutableStateOf(emptyList<GroceryItem>()) }
    var reloadNonce by remember { mutableStateOf(0) }
    var confirmingDone by remember { mutableStateOf(false) }
    var lastSummary by remember { mutableStateOf<TripSummary?>(null) }

    LaunchedEffect(reloadNonce) {
        val items = GroceryController.items(context)
        rawItems = items
        state = GroceryUiState(
            loading = false,
            rows = buildGroceryRows(items),
            suggestions = GroceryController.suggestions(context).map { it.displayName },
        )
    }

    GroceryContent(
        state = state,
        onAdd = { text -> scope.launch { GroceryController.addItem(context, text); reloadNonce++ } },
        onToggle = { id ->
            val target = rawItems.firstOrNull { it.id == id } ?: return@GroceryContent
            scope.launch {
                if (target.done) GroceryController.untick(context, target) else GroceryController.tick(context, target)
                reloadNonce++
            }
        },
        onRemove = { id ->
            val target = rawItems.firstOrNull { it.id == id } ?: return@GroceryContent
            scope.launch { GroceryController.removeItem(context, target); reloadNonce++ }
        },
        onDone = { confirmingDone = true },
        lastSummary = lastSummary,
        onDismissSummary = { lastSummary = null },
    )

    if (confirmingDone) {
        val skipped = state.rows.count { !it.done }
        val bought = state.rows.count { it.done }
        AlertDialog(
            onDismissRequest = { confirmingDone = false },
            title = { Text("Finish this trip?") },
            text = {
                Column {
                    // The destructive part first, in plain words, with the number attached.
                    Text("This clears the whole list.")
                    Text(
                        if (skipped > 0) {
                            "$bought ticked item(s) will be remembered for next time. " +
                                "$skipped item(s) you never ticked will be deleted and NOT remembered."
                        } else {
                            "$bought ticked item(s) will be remembered for next time."
                        },
                        style = LegionType.stamp,
                        color = LocalLegionSemantics.current.faint,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDone = false
                    scope.launch {
                        lastSummary = GroceryController.completeTrip(context)
                        reloadNonce++
                    }
                }) { Text("Finish") }
            },
            dismissButton = { TextButton(onClick = { confirmingDone = false }) { Text("Keep shopping") } },
        )
    }
}

/** Plain UI: [state] plus callbacks, no controller/DB reference - see the file doc comment. */
@Composable
fun GroceryContent(
    state: GroceryUiState,
    onAdd: (String) -> Unit,
    onToggle: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onDone: () -> Unit,
    lastSummary: TripSummary? = null,
    onDismissSummary: () -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    var addText by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (state.tripInProgress) "${state.remaining} left" else "No trip in progress",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
                // DONE only exists while there is something to finish - a button that tears down an
                // empty list is a button with nothing to do and a destructive-sounding label.
                if (state.tripInProgress) {
                    TextButton(onClick = onDone) {
                        Text("DONE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            DashedHairline()

            // The just-finished trip's receipt-of-sorts. Shown until dismissed rather than as a
            // transient snackbar: it is the only confirmation that a destructive action did what
            // the driver expected, and it must not vanish before it is read.
            lastSummary?.let { summary ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        "Trip finished - ${summary.bought} bought, ${summary.skipped} dropped unticked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (summary.bought > 0) {
                        Text(
                            "Remembered for next time: ${summary.boughtNames.joinToString(", ")}",
                            style = LegionType.stamp,
                            color = sem.faint,
                        )
                    }
                    TextButton(onClick = onDismissSummary) {
                        Text("DISMISS", style = LegionType.stamp, color = sem.faint)
                    }
                }
                DashedHairline()
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = addText,
                    onValueChange = { addText = it },
                    label = { Text("Add to the list") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = addText.isNotBlank(),
                    onClick = { onAdd(addText); addText = "" },
                ) {
                    Text(
                        "ADD",
                        style = LegionType.stamp,
                        color = if (addText.isNotBlank()) MaterialTheme.colorScheme.primary else sem.ghost,
                    )
                }
            }

            if (state.suggestions.isNotEmpty()) {
                DeckPane(header = "USUALLY BUY", headerAccent = state.suggestions.size.toString()) {}
                // Horizontally scrollable rather than wrapping: FlowRow is still an experimental
                // layout API, and this row is short by construction (SUGGESTION_LIMIT caps it).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.suggestions.forEach { name ->
                        TextButton(onClick = { onAdd(name) }) {
                            Text("+ $name", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                DashedHairline()
            }

            if (state.loading) {
                Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
                return@Column
            }

            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "grocery-header") {
                    DeckPane(header = "GROCERY", headerAccent = state.remaining.toString()) {}
                }
                if (state.rows.isEmpty()) {
                    item(key = "grocery-empty") {
                        Text(
                            "Nothing on the list. Add something above, or tap one you usually buy.",
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.faint,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                } else {
                    items(state.rows, key = { it.id }) { row ->
                        GroceryRow(row = row, onToggle = { onToggle(row.id) }, onRemove = { onRemove(row.id) })
                        DashedHairline()
                    }
                }
            }
        }
    }
}

/**
 * One line of the trip. Bigger tap target than [InboxRow]'s - this is used walking round a shop,
 * one-handed, and the whole row toggles rather than just the checkbox.
 */
@Composable
private fun GroceryRow(row: GroceryRowView, onToggle: () -> Unit, onRemove: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.done, onCheckedChange = { onToggle() })
        Text(
            row.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (row.done) sem.faint else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (row.done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRemove) { Text("REMOVE", style = LegionType.stamp, color = sem.faint) }
    }
}

// ------------------------------------------------------------------------ previews

private val previewRows = listOf(
    GroceryRowView(id = 1, text = "Milk", done = false),
    GroceryRowView(id = 2, text = "Coffee beans", done = false),
    GroceryRowView(id = 3, text = "Bread", done = false),
    GroceryRowView(id = 4, text = "Eggs", done = true),
)

@Preview(name = "Grocery: active trip", widthDp = 400, heightDp = 720)
@Composable
private fun PreviewGroceryActive() = LegionTheme {
    GroceryContent(
        GroceryUiState(loading = false, rows = previewRows, suggestions = listOf("Butter", "Bananas")),
        onAdd = {}, onToggle = {}, onRemove = {}, onDone = {},
    )
}

@Preview(name = "Grocery: no trip, with staples", widthDp = 400, heightDp = 720)
@Composable
private fun PreviewGroceryEmpty() = LegionTheme {
    GroceryContent(
        GroceryUiState(loading = false, suggestions = listOf("Milk", "Eggs", "Bread", "Coffee beans")),
        onAdd = {}, onToggle = {}, onRemove = {}, onDone = {},
    )
}

@Preview(name = "Grocery: just finished a trip", widthDp = 400, heightDp = 720)
@Composable
private fun PreviewGroceryFinished() = LegionTheme {
    GroceryContent(
        GroceryUiState(loading = false, suggestions = listOf("Milk", "Eggs")),
        onAdd = {}, onToggle = {}, onRemove = {}, onDone = {},
        lastSummary = TripSummary(bought = 3, skipped = 1, boughtNames = listOf("Milk", "Eggs", "Bread")),
    )
}
