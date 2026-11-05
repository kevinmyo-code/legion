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
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ui.PurgeLedgerRow
import com.kevin.legion.ui.SettingsNavRow
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch


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

    // Null until the phone has a copy of the engine's list: "not read yet" is not "nothing remembered".
    var storedMemoryCount by remember { mutableStateOf<Int?>(null) }
    var reloadNonce by remember { mutableStateOf(0) }

    // Memory v2 lives on the engine (memory-and-vault ticket 11); this reads the phone's copy of its
    // whole list, with no network, so the line never waits on the engine.
    suspend fun reloadMemoryStatus() {
        storedMemoryCount = com.kevin.legion.memory.MemoryController.forContext(context).cached()?.memories?.size
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
                    status = when (val count = storedMemoryCount) {
                        null -> "On the engine; not read on this phone yet"
                        0 -> "Nothing remembered yet"
                        else -> "$count memories on the engine"
                    },
                    onClick = onOpenMemory,
                )

                // memory-and-vault ticket 12 (ADR 0076): the ID vault opens in its own activity,
                // behind the fingerprint, the same door `show_vault_item` uses.
                SettingsNavRow(
                    label = "ID vault",
                    status = "Passport, SSN and account numbers, behind your fingerprint",
                    onClick = { com.kevin.legion.ui.vault.VaultActivity.open(context, label = null) },
                )

                // Recordings moved off this screen 2026-09-04 (Kevin: "it needs a place on the
                // home screen... recordings needs its own ui") - three taps into a privacy screen
                // was the wrong home for something meant to be started in one tap. It now lives on
                // `ui/MetersScreen.kt`'s own RECORDINGS pane, with a RECORD control alongside it,
                // same pattern that pane's LISTS section already uses for the persistent list and
                // the grocery trip. [LegionRoute.SETTINGS_VOICE_NOTES] is unchanged - only what
                // navigates to it moved.

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
