package com.kevin.legion.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kevin.legion.service.ReadThroughCleanup
import com.kevin.legion.ui.SettingsNavRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kevin's one control over the ADR 0090 cleanup (2026-11-17, "Blank them", counts first): the row
 * shows what the automatic dry run found, and blanking happens only after he taps it and says yes.
 * Nothing is deleted; memories are listed for review, never changed.
 */
@Composable
fun ReadThroughCleanupRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(ReadThroughCleanup.lastSummary(context)) }
    var asking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    SettingsNavRow(
        label = "Blank old mail and brief copies",
        status = when {
            busy -> "Working..."
            summary != null -> summary!!
            else -> "Not checked yet. The check runs once by itself after this update."
        },
        onClick = { if (!busy) asking = true },
    )
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Blank them?") },
            text = {
                Text(
                    "Before the 2026-11-17 fix the laptop voice kept some mail, brief, parcel and flight " +
                        "answers in clear. This replaces that text on this phone with a redaction mark. " +
                        "Nothing is deleted and no memory is changed. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    asking = false
                    busy = true
                    scope.launch {
                        val report = withContext(Dispatchers.IO) { ReadThroughCleanup.run(context, real = true) }
                        summary = report.summary()
                        busy = false
                    }
                }) { Text("Blank them") }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Not now") } },
        )
    }
}
