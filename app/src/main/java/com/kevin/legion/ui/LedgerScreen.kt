package com.kevin.legion.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import com.kevin.legion.ledger.LedgerController

/**
 * `ledger` tab host. What this screen looks like is ticket 08's job (out of
 * scope here) - this is the minimal host that compiles, renders, and makes
 * `ledger/import` reachable.
 */
@Composable
fun LedgerScreen(onOpenImport: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Ledger - not built yet. See ticket 08.")
            Button(onClick = onOpenImport) {
                Text("Import statement")
            }
        }
    }
}

/**
 * `ledger/import` - absorbed from the deleted `LedgerImportActivity`. Content
 * unchanged (`ACTION_OPEN_DOCUMENT` picks a PDF, [LedgerController] ingests
 * it, the result - success/count, or the quarantine reason - is shown); only
 * the hosting changed, and the activity-result launcher moved from
 * `registerForActivityResult` (needs an Activity) to
 * `rememberLauncherForActivityResult` (the Compose-native equivalent, now
 * that this is a screen inside the shell rather than its own Activity).
 */
@Composable
fun LedgerImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Pick a bank statement PDF to import.") }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingUri = uri
    }

    LaunchedEffect(pendingUri) {
        val current = pendingUri ?: return@LaunchedEffect
        status = "Importing..."
        status = LedgerController.importStatement(context, current).message
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = onBack) {
                Text("< Back")
            }
            Text(status)
            Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) {
                Text("Pick statement PDF")
            }
        }
    }
}
