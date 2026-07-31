package com.kevin.legion.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kevin.legion.ledger.LedgerController

/**
 * Placeholder for the import_statement voice tool (`.claude/plans/wiggly-
 * beaming-quasar.md`) - same posture as [SavedPlacesActivity]: functional,
 * not designed. `ACTION_OPEN_DOCUMENT` picks a PDF, [LedgerController]
 * ingests it, the result (success/count, or the quarantine reason) is shown.
 */
class LedgerImportActivity : ComponentActivity() {
    private var pendingUri = mutableStateOf<Uri?>(null)

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingUri.value = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LedgerImportScreen(pendingUri, onPickFile = { pickPdf.launch(arrayOf("application/pdf")) })
        }
    }
}

@Composable
private fun LedgerImportScreen(
    pendingUri: androidx.compose.runtime.MutableState<Uri?>,
    onPickFile: () -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Pick a bank statement PDF to import.") }
    val uri by pendingUri

    LaunchedEffect(uri) {
        val current = uri ?: return@LaunchedEffect
        status = "Importing..."
        status = LedgerController.importStatement(context, current).message
    }

    MaterialTheme {
        Surface {
            Column {
                Text(status)
                Button(onClick = onPickFile) {
                    Text("Pick statement PDF")
                }
            }
        }
    }
}
