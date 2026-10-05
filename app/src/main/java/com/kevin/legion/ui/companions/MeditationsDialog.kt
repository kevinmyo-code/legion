package com.kevin.legion.ui.companions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.kevin.legion.meditations.Meditations
import com.kevin.legion.meditations.MeditationsLookup
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The hands path to `consult_meditations` (ADR 0035: every voice capability has a non-voice path).
 *
 * A read-only search box over the bundled text, reached from the Marcus companion's row on the
 * Companions screen. It calls [MeditationsLookup.run], the very function the voice tool calls, so a
 * typed question and a spoken one return the same passages. Nothing here is stored: a query and its
 * results live in this composition only.
 *
 * The Project Gutenberg sentence (licence paragraph 1.E.1, `assets/meditations/NOTICE.txt`) is shown
 * under the results, and the full licence ships beside the text in the same folder.
 */
@Composable
fun MeditationsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current
    var query by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<MeditationsLookup.Outcome?>(null) }

    fun ask() {
        scope.launch {
            outcome = withContext(Dispatchers.Default) {
                MeditationsLookup.run(Meditations.search(context), query)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Meditations") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("A topic, or Book IV, 3") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = ::ask, modifier = Modifier.padding(top = 8.dp)) { Text("Search") }
                Spacer(Modifier.padding(top = 8.dp))
                when (val o = outcome) {
                    null -> Unit
                    MeditationsLookup.Outcome.Blank ->
                        Text("Type a topic first.", style = LegionType.stamp, color = sem.faint)
                    MeditationsLookup.Outcome.NoMatch ->
                        Text("Nothing in the Meditations matched that.", style = LegionType.stamp, color = sem.faint)
                    // The same refusal the voice tool gives: the text is not an answer to distress.
                    MeditationsLookup.Outcome.Distress -> Text(
                        "This is not a question for a book. If you are in distress, call or text 988 " +
                            "(US) to reach the Suicide and Crisis Lifeline, any time.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is MeditationsLookup.Outcome.Found -> o.hits.forEach { hit ->
                        Text(
                            hit.passage.cite + if (hit.isExcerpt) " (part of the section)" else "",
                            style = LegionType.stamp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            hit.text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
                Text(
                    "George Long's 1862 translation, from Project Gutenberg eBook #15877. This eBook is " +
                        "for the use of anyone anywhere in the United States and most other parts of the " +
                        "world at no cost and with almost no restrictions whatsoever. You may copy it, " +
                        "give it away or re-use it under the terms of the Project Gutenberg License " +
                        "included with this eBook or online at www.gutenberg.org.",
                    style = LegionType.stamp,
                    color = sem.ghost,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
