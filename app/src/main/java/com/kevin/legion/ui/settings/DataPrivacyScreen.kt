package com.kevin.legion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ui.PurgeLedgerRow
import com.kevin.legion.ui.SettingsNavRow
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * Shared with `settings/memory`'s own MEMORY_LIMIT - kept as a separate named constant here rather
 * than importing that private one, since this screen only needs it to keep its own headline count
 * from ever exceeding what the destination screen can actually show. Unchanged from the old
 * monolith's own copy of this constant.
 */
private const val MEMORY_SETTINGS_SCAN = 200

/**
 * "Data and privacy" - the fourth subscreen `settings/` split into (command-center ticket 02).
 * Owns what the app remembers ([com.kevin.legion.ui.companions.MemoryScreen]) and the one
 * destructive action on this screen ([PurgeLedgerRow]), plus a plain-language statement of the
 * read-through rule (`.scratch/command-center/map.md` ruling 1) - the CLAUDE.md §4 gate stated in
 * words a user reads, not the engineering prose that governs it.
 */
@Composable
fun DataPrivacyScreen(onBack: () -> Unit, onOpenMemory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var storedMemoryCount by remember { mutableStateOf(0) }
    var reloadNonce by remember { mutableStateOf(0) }

    // Both counts are capped at MEMORY_SETTINGS_SCAN - `settings/memory`'s own screen fetches with
    // the same limit, so this status line can never claim a bigger number than that screen can
    // actually show, rather than running a second, uncapped query just to headline a count.
    suspend fun reloadMemoryStatus() {
        val db = CarDatabase.getDatabase(context)
        storedMemoryCount = db.memoryDao().getRecent(MEMORY_SETTINGS_SCAN).size +
            db.companionMemoryDao().allRecent(MEMORY_SETTINGS_SCAN).size
    }

    LaunchedEffect(reloadNonce) { reloadMemoryStatus() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reloadNonce++ }

    val sem = LocalLegionSemantics.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Data and privacy", onBack = onBack)
            Column(
                Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "What's stored, in plain words",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Bank transactions, receipts, car data, and anything you or your companion " +
                        "have explicitly remembered - all on this phone, or in your own Google " +
                        "Drive if you've connected it. None of it goes through a server I run.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "News, email, and calendar items shown on Today are never stored - they're " +
                        "fetched fresh each time and thrown away right after. Close the app and " +
                        "they're gone until it fetches again.",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
                Spacer(Modifier.height(16.dp))

                // Mission-control playbook/memory build (2026-08-18): what the assistant has
                // actually remembered, previously invisible and uneditable outside a rebuild.
                SettingsNavRow(
                    label = "Memory",
                    status = if (storedMemoryCount > 0) {
                        "$storedMemoryCount memories stored"
                    } else {
                        "Nothing remembered yet"
                    },
                    onClick = onOpenMemory,
                )

                // Mission-control ticket 16: re-homed from CRED's own root (ticket 12's ruling - "a
                // destructive purge does not belong on a surface you open daily"). Kept last on
                // this screen too, same reasoning: putting a not-undoable action anywhere above the
                // content it destroys invites the mis-tap it exists to make deliberate.
                Spacer(Modifier.height(16.dp))
                PurgeLedgerRow(
                    onPurge = {
                        scope.launch { LedgerController.purgeAll(context) }
                    },
                )

                // Data-source credits. These are LICENCE TERMS, not courtesy: Open-Meteo ships
                // under CC BY 4.0, which requires a visible attribution with a link, and TomTom's
                // developer terms require the traffic credit. Neither appeared anywhere a user
                // could see until hardening ticket 07; every mention in the codebase was a code
                // comment. The repo is public, so this was visible.
                //
                // Placed here rather than on a route of its own because this screen already
                // answers "where does what you see come from, and what is kept". Anything added
                // that reaches a network for data belongs in this list, and a new feed with no
                // credit is a bug in the same way a voice tool with no copy is.
                Spacer(Modifier.height(24.dp))
                Text(
                    "DATA SOURCES",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Weather data by Open-Meteo.com, used under CC BY 4.0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Traffic flow data (c) TomTom.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Earthquake, weather-alert, flood and wildfire data courtesy of the U.S. " +
                        "Geological Survey, the National Weather Service, FEMA and the National " +
                        "Interagency Fire Center, which are U.S. public domain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
