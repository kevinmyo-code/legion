@file:Suppress("MagicNumber") // layout sizes

package com.kevin.legion.ui.conditions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * Conditions around here or a saved place (round 5): plain on purpose, existing components only,
 * so the Typeset restyle swaps the look without touching [ConditionsViewModel].
 */
@Composable
fun ConditionsScreen(onBack: () -> Unit) {
    val viewModel: ConditionsViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    SoftTheme {
        ConditionsContent(state, onBack, viewModel::select, viewModel::refresh)
    }
}

@Composable
internal fun ConditionsContent(
    state: ConditionsUiState,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        DeckScreenHeader(title = "Conditions", onBack = onBack, accent = AreaAccent.FLEET)
        Spacer(Modifier.height(8.dp))
        state.choices.forEach { choice ->
            DeckButton(
                text = if (choice.label == state.selected) "${choice.label} (showing)" else choice.label,
                onClick = { onSelect(choice.label) },
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        }
        DeckButton(
            text = if (state.loading) "Checking..." else "Check again",
            onClick = onRefresh,
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        state.problem?.let {
            DeckPane(header = "Nothing read") { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        state.sections.forEach { section ->
            Spacer(Modifier.height(8.dp))
            DeckPane(header = SECTION_TITLES[section.kind] ?: section.kind) {
                Text(section.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val SECTION_TITLES = mapOf(
    "alerts" to "Weather alerts",
    "rivers" to "Rivers and flooding",
    "storms" to "Tropical storms",
    "roads" to "Road closures",
    "fire" to "Fire detections",
)
