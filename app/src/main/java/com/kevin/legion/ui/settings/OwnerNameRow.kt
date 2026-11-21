package com.kevin.legion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.OwnerName
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings > Assistant > "What should the assistant call you?" (2026-11-20 ruling: the app may be
 * given his name). The one place on the phone to set it ([OwnerName]); blank uses the name on his
 * engine account, and with neither the assistant uses no name. Applies from the next conversation.
 */
@Composable
fun OwnerNameRow(companionName: String?) {
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    var text by remember { mutableStateOf(OwnerName.chosen(context)) }
    var engineName by remember { mutableStateOf(OwnerName.clean(OwnerName.engine(context))) }
    var status by remember { mutableStateOf<String?>(null) }

    // Re-read the engine's name for his account when the row opens (at most once a day).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { OwnerName.refreshFromEngine(context) }
        engineName = OwnerName.clean(OwnerName.engine(context))
    }

    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "What should ${companionName ?: "the assistant"} call you?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                when (val used = OwnerName.resolve(text, engineName)) {
                    null -> "No name set, so it uses none. Applies from the next conversation."
                    else -> "It calls you $used now and then. Leave blank to use your engine account's " +
                        "name. Applies from the next conversation."
                },
                style = LegionType.stamp,
                color = sem.faint,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; status = null },
                singleLine = true,
                label = { Text("First name") },
                placeholder = { engineName?.let { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            DeckButton(
                text = "Save name",
                onClick = {
                    OwnerName.setChosen(context, text)
                    status = "Saved."
                },
            )
            status?.let { Text(it, style = LegionType.stamp, color = sem.faint) }
        }
    }
}
