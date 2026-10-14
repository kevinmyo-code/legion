package com.kevin.midnightai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.kevin.midnightai.ai.CUSTOM_KEY
import com.kevin.midnightai.ai.PERSONA_STAGES
import com.kevin.midnightai.ai.PersonaSelection
import com.kevin.midnightai.ui.theme.AriaColors

/**
 * The guided personality picker: walks [PERSONA_STAGES] one at a time, each a
 * question with three tappable presets plus a "Type your own" option, so the
 * driver shapes the persona without typing a system prompt on a head-unit
 * keyboard. Tapping a preset advances immediately; the custom option reveals a
 * text field and a Next/Finish button. [onComplete] fires with the per-stage
 * selections once the last stage is answered. Reusable in onboarding and (later)
 * the settings editor; [initial] pre-selects choices when re-editing.
 */
@Composable
fun PersonaPicker(
    initial: Map<String, PersonaSelection> = emptyMap(),
    onComplete: (Map<String, PersonaSelection>) -> Unit,
    onBack: () -> Unit,
) {
    val selections = remember { mutableStateMapOf<String, PersonaSelection>().apply { putAll(initial) } }
    var stageIndex by remember { mutableIntStateOf(0) }
    val stage = PERSONA_STAGES[stageIndex]
    val isLast = stageIndex == PERSONA_STAGES.lastIndex

    // Custom-entry fields, reset when the stage changes. Kept UNKEYED (with a
    // LaunchedEffect reset) so the voice launcher's callback always writes to the
    // live state rather than a stale stage's captured state.
    var customMode by remember { mutableStateOf(selections[stage.key]?.choiceKey == CUSTOM_KEY) }
    var customText by remember { mutableStateOf(selections[stage.key]?.customText.orEmpty()) }
    LaunchedEffect(stageIndex) {
        customMode = selections[stage.key]?.choiceKey == CUSTOM_KEY
        customText = selections[stage.key]?.customText.orEmpty()
    }

    fun advance() {
        if (isLast) onComplete(selections.toMap()) else stageIndex++
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AriaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            text = "‹ BACK",
            style = MaterialTheme.typography.labelSmall,
            color = AriaColors.Amber,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { if (stageIndex > 0) stageIndex-- else onBack() }
                .padding(vertical = 6.dp, horizontal = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "PERSONALITY · ${stageIndex + 1} of ${PERSONA_STAGES.size}",
            style = MaterialTheme.typography.labelSmall,
            color = AriaColors.TextDim,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stage.question,
            style = MaterialTheme.typography.headlineSmall,
            color = AriaColors.TextPrimary,
        )
        Spacer(Modifier.height(18.dp))

        for (choice in stage.choices) {
            ChoiceCard(
                label = choice.label,
                selected = !customMode && selections[stage.key]?.choiceKey == choice.key,
                onClick = {
                    customMode = false
                    selections[stage.key] = PersonaSelection(choice.key)
                    advance()
                },
            )
            Spacer(Modifier.height(10.dp))
        }

        ChoiceCard(
            label = "✎ Type your own",
            selected = customMode,
            onClick = { customMode = true },
        )

        if (customMode) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                label = { Text("Describe this in your own words") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    selections[stage.key] = PersonaSelection(CUSTOM_KEY, customText.trim())
                    advance()
                },
                enabled = customText.isNotBlank(),
            ) { Text(if (isLast) "FINISH" else "NEXT") }
        }
    }
}

@Composable
private fun ChoiceCard(label: String, selected: Boolean, onClick: () -> Unit) {
    val accent = if (selected) AriaColors.Amber else AriaColors.PanelBorder
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, accent, RoundedCornerShape(10.dp))
            .background(AriaColors.Panel)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 18.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) AriaColors.Amber else AriaColors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}
