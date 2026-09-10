package com.kevin.legion.ui.ask

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.GeneratedViewController
import com.kevin.legion.service.GeneratedViewQueryRunner
import com.kevin.legion.service.GeneratedViewQuerySpec
import com.kevin.legion.service.GeneratedViewShape
import com.kevin.legion.service.QueryAggregation
import com.kevin.legion.service.QueryGrouping
import com.kevin.legion.service.QuerySource
import com.kevin.legion.service.QueryWindow
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import kotlinx.coroutines.launch

/**
 * The hands path for `show_generated_view` (ADR 0035) - moved out of `ui/MetersScreen.kt` into its
 * own route (one-home ticket 02, `.scratch/one-home/issues/02-rehome-the-orphans.md`), which that
 * ticket's own text calls the reason this whole ticket is a gate: "delete the panel and
 * `show_generated_view` becomes a voice-only capability, which ADR 0035 says is not finished."
 *
 * **Calls the SAME [GeneratedViewQueryRunner]/[GeneratedViewController] a voice call uses** - not a
 * second implementation, ADR 0035's own "not a second implementation" clause, and the reason this
 * screen has no free-text field. Every field is a tap-to-cycle picker over the SAME closed enum the
 * voice tool validates against ([GeneratedViewShape]/[QuerySource]/[QueryAggregation]/
 * [QueryWindow]/[QueryGrouping]) - never a free-text query, so this hand path cannot express
 * anything the voice path could not also be asked to build. [askRefusal] is the refusal path
 * carried across verbatim from the old pane - a refusal that stops being rendered is a silent
 * failure, ticket 02's own words.
 */
@Composable
fun AskScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var askShape by remember { mutableStateOf(GeneratedViewShape.TOTAL_WITH_ROWS) }
    var askSource by remember { mutableStateOf(QuerySource.LEDGER) }
    var askAggregation by remember { mutableStateOf(QueryAggregation.SUM) }
    var askWindow by remember { mutableStateOf(QueryWindow.THIS_MONTH) }
    var askGrouping by remember { mutableStateOf(QueryGrouping.NONE) }
    var askRefusal by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
        DeckPane(header = "Ask", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            DeckRow(
                label = "Shape",
                value = askShape.name,
                modifier = Modifier.clickable { askShape = cycle(askShape) },
            )
            DeckRow(
                label = "Source",
                value = askSource.name,
                modifier = Modifier.clickable { askSource = cycle(askSource) },
            )
            DeckRow(
                label = "Aggregation",
                value = askAggregation.name,
                modifier = Modifier.clickable { askAggregation = cycle(askAggregation) },
            )
            DeckRow(
                label = "Window",
                value = askWindow.name,
                modifier = Modifier.clickable { askWindow = cycle(askWindow) },
            )
            DeckRow(
                label = "Grouping",
                value = askGrouping.name,
                modifier = Modifier.clickable { askGrouping = cycle(askGrouping) },
            )
            DeckRow(
                label = "Run",
                value = askRefusal ?: "tap to build",
                modifier = Modifier.clickable {
                    val spec = GeneratedViewQuerySpec(
                        shape = askShape,
                        source = askSource,
                        aggregation = askAggregation,
                        window = askWindow,
                        grouping = askGrouping,
                        title = "${askSource.name} - ${askShape.name}",
                    )
                    scope.launch {
                        when (val run = GeneratedViewQueryRunner.run(context, spec)) {
                            is GeneratedViewQueryRunner.RunResult.Refusal -> askRefusal = run.reason
                            is GeneratedViewQueryRunner.RunResult.Rendered -> {
                                askRefusal = null
                                GeneratedViewController.show(run.payload)
                            }
                        }
                    }
                },
            )
        }
    }
}

/** Cycles [current] to the next member of its own enum, wrapping - the tap-to-cycle picker every
 * [DeckRow] above uses, so choosing a value never opens a second surface. Moved verbatim from
 * `ui/MetersScreen.kt` alongside the rest of the ASK pane. */
private inline fun <reified T : Enum<T>> cycle(current: T): T {
    val values = enumValues<T>()
    return values[(current.ordinal + 1) % values.size]
}
