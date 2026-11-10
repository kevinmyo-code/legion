package com.kevin.legion.look

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.ai.CompanionProfile
import kotlinx.coroutines.launch

/**
 * The hands path for "look at this" (ADR 0084, ADR 0035): the `look` voice tool's twin, calling the
 * same [LookController]. Labelled with the assistant's own name, never a hardcoded one. A refusal
 * (moving, no screen) is said under the button in words.
 */
@Composable
fun ShowToAssistantButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refusal by remember { mutableStateOf<String?>(null) }
    val name = CompanionProfile.name(context).trim().ifBlank { "the assistant" }
    Column(modifier) {
        TextButton(onClick = { scope.launch { refusal = LookController.openByHand(context) } }) {
            Text(
                "Show $name something (camera)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        refusal?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
