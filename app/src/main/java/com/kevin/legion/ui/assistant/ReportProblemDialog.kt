package com.kevin.legion.ui.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * The button half of "report a problem" (alfred-builds ticket 01), opened by pressing and holding a
 * reply. The note is optional; [onSend] hands the same controller the voice tool uses what he typed.
 * It says in words that only his note is sent, never the reply: a reply can quote mail.
 */
@Composable
internal fun ReportProblemDialog(onSend: (note: String) -> Unit, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a problem") },
        text = {
            Column {
                Text("Sends your note to Claude to look into. The reply's own words are not sent.")
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("What was wrong (optional)") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(note) }) { Text("Send report") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
