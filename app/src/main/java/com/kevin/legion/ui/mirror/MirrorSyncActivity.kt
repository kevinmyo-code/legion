package com.kevin.legion.ui.mirror

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.engine.mirror.MirrorFolderPreferences
import com.kevin.legion.engine.mirror.MirrorStateStore
import com.kevin.legion.engine.mirror.MirrorSync
import com.kevin.legion.ui.theme.LegionTheme
import kotlinx.coroutines.launch

/**
 * The mirror/sync settings stub (ticket 20 item 5) - pick-folder, sync-now, and last-sync/
 * quarantine state, nothing more. Registered `exported="false"`, no launcher `<intent-filter>`,
 * same debug-entry posture as `.ui.widgets.WidgetPagerActivity` (see that activity's own doc
 * comment for the precedent):
 *
 * `adb shell am start -n com.kevin.legion/.ui.mirror.MirrorSyncActivity`
 *
 * `MirrorFolderPreferences.init` now runs in `MidnightApplication.onCreate` (senior review of
 * ticket 20, MUST-FIX 2) so `MirrorLifecycleBinder`'s foreground/background triggers see a live
 * connection state from process start, not just after this activity happens to be opened once -
 * this activity no longer calls `init` itself.
 *
 * **This is the FIRST thing to run on the A25** (ticket 20's own instruction: "the on-A25 probe...
 * left owed... is the FIRST thing that must run on the phone"). "Sync now" on a freshly connected
 * folder IS the probe: it creates one xlsx per active aspect, rewrites it via `rwt`, reads it back,
 * and hash-verifies - exactly research 01's owed steps 2 and 3, exercised for real rather than
 * simulated. A future ticket may host this stub's content inside `MainActivity`'s own `NavHost`
 * (the same absorption `AndroidManifest.xml` already describes for ledger/pantry) once the mirror
 * is not still probe-first work.
 */
class MirrorSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LegionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MirrorSyncScreen()
                }
            }
        }
    }
}

@Composable
private fun MirrorSyncScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val treeUri by MirrorFolderPreferences.treeUri.collectAsStateWithLifecycle()
    val mirrorSync = remember { MirrorSync(context) }
    val lastResult by mirrorSync.lastResult.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<MirrorFolderPreferences.ConnectionStatus?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(treeUri) {
        status = MirrorFolderPreferences.connectionStatus(context)
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) MirrorFolderPreferences.connect(context, uri)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mirror sync", style = MaterialTheme.typography.headlineSmall)
        Text(
            "One xlsx workbook per aspect in a Drive folder you pick. Bounded staleness: whatever " +
                "was true at the last sync, not live. Bring your Drive folder in Sheets when you " +
                "want to hand-edit it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        // SHOULD-FIX 4, senior review of ticket 20: the dropdowns/highlighting the spreadsheet
        // shows are generated for convenience only - fastexcel has no per-cell lock API (see
        // MirrorCodec's own doc comment) and neither Sheets nor Excel mobile is confirmed to
        // enforce embedded xlsx validation rules (research 02). Said in words here so this is
        // never mistaken for real enforcement by whoever is looking at the sheet.
        Text(
            "The dropdowns and highlighting in the spreadsheet are a convenience, not a rule - " +
                "LEGION's own import check is what actually accepts or rejects an edit, every time you sync.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text(
            when {
                status?.uri == null -> "No folder connected."
                status?.permissionGranted == false -> "Folder '${status?.displayName}' was connected but the permission was revoked - pick it again."
                else -> "Connected: ${status?.displayName}"
            },
        )

        Button(onClick = { pickFolder.launch(null) }) {
            Text(if (treeUri == null) "Pick mirror folder" else "Change mirror folder")
        }

        if (treeUri != null) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        mirrorSync.exportNow()
                        busy = false
                    }
                },
            ) {
                Text(if (busy) "Syncing…" else "Sync now")
            }
        }

        val knownAspects = remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(lastResult) {
            val aspects = CarDatabase.getDatabase(context).aspectDao().listActive()
            knownAspects.value = aspects.map { it.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
        }

        Text("Per-aspect state", style = MaterialTheme.typography.titleMedium)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(knownAspects.value) { slug ->
                val s = MirrorStateStore.get(context, slug)
                val line = buildString {
                    append(slug)
                    append(": ")
                    if (s.quarantined) {
                        append("QUARANTINED - ")
                        append(s.quarantineReason ?: "unknown reason")
                    } else {
                        append("last export ")
                        append(s.lastExportAt?.let { "$it" } ?: "never")
                        append(", last import ")
                        append(s.lastImportAt?.let { "$it" } ?: "never")
                    }
                }
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }

        lastResult?.let { result ->
            Text("Last run", style = MaterialTheme.typography.titleMedium)
            Text("Exported: ${result.exported.joinToString(", ").ifBlank { "none" }}")
            if (result.exportFailures.isNotEmpty()) {
                Text("Export failures: " + result.exportFailures.entries.joinToString("; ") { "${it.key}: ${it.value}" })
            }
            result.importSummaries.forEach { (slug, summary) ->
                Text(
                    "$slug import: ${summary.created} created, ${summary.updated} updated, " +
                        "${summary.unchanged} unchanged, ${summary.trashed} trashed",
                )
                summary.quarantined.forEach { Text("  quarantined: $it", style = MaterialTheme.typography.bodySmall) }
                summary.definitionWarnings.forEach { Text("  definitions: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
