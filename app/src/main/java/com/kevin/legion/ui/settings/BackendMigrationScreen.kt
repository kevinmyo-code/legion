package com.kevin.legion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.backend.EventsReconcile
import com.kevin.legion.backend.FleetReconcile
import com.kevin.legion.backend.MembershipResult
import com.kevin.legion.backend.PantryReconcile
import com.kevin.legion.backend.PlacesReconcile
import com.kevin.legion.backend.SupabaseAuth
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabaseConfig
import com.kevin.legion.backend.SupabaseEventsBackend
import com.kevin.legion.backend.SupabaseFleetBackend
import com.kevin.legion.backend.SupabasePantryBackend
import com.kevin.legion.backend.SupabasePlacesBackend
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.failureReason
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.LegionTheme
import kotlinx.coroutines.launch

/**
 * `settings/backend-migration` - the hands path for backend-erp Phase 4's three reconciles
 * ([PlacesReconcile]/[PantryReconcile]/[EventsReconcile]), which as of Phase 4's own ticket had
 * zero production callers: fully built, tested, and unreachable. Reachable from
 * [ConnectionsScreen] the same way `settings/drive-sync` is reachable from `settings/google` -
 * one row, one destination, this screen owns the actual action.
 *
 * **This is the first screen in the app that writes to a live server the household did not
 * build.** Every row states plainly, ahead of the button, what running it does (uploads this
 * aspect's engine records to the household's own Supabase project, refills the on-device
 * replica, changes nothing on the engine side) - task brief: a clear label is enough for a
 * maintenance screen the user navigated to deliberately, no confirmation dialog.
 *
 * **Same state-holder/content split as every other backend-erp screen** (`.claude/skills/
 * compose-state-holder-ui-split`): [BackendMigrationScreen] owns [SupabaseClientProvider]/
 * [SupabaseAuth]/coroutine plumbing and the real reconcile calls; [BackendMigrationContent] is
 * plain state-plus-callbacks so it previews and screenshot-tests with no Context, no network, no
 * Robolectric. [BackendMigrationResolver] is the pure report-to-words/readiness layer both this
 * file and its test target.
 */
@Composable
fun BackendMigrationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val supabaseAuth = remember { SupabaseAuth(context) }

    var configured by remember { mutableStateOf(SupabaseConfig.isConfigured(context)) }
    var membership by remember { mutableStateOf<MembershipResult?>(null) }

    var places by remember { mutableStateOf(ReconcileRowUiState()) }
    var pantry by remember { mutableStateOf(ReconcileRowUiState()) }
    var events by remember { mutableStateOf(ReconcileRowUiState()) }
    var fleet by remember { mutableStateOf(ReconcileRowUiState()) }

    // Re-checked on every resume, same reasoning as ConnectionsScreen/KeyScreen: coming back
    // from the Gemini key screen having just configured or signed in is exactly the moment this
    // needs to be fresh.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        configured = SupabaseConfig.isConfigured(context)
        scope.launch {
            membership = if (configured) supabaseAuth.isHouseholdMember() else null
        }
    }

    fun runPlaces() {
        places = places.copy(running = true, resultLines = null, failure = null)
        scope.launch {
            val client = SupabaseClientProvider.get(context)
            if (client == null) {
                places = places.copy(running = false, failure = BackendMigrationResolver.renderFailure("Supabase is not configured"))
                return@launch
            }
            val result = PlacesReconcile.run(context, SupabasePlacesBackend(client))
            places = result.fold(
                onSuccess = { report -> ReconcileRowUiState(resultLines = BackendMigrationResolver.renderPlacesReport(report)) },
                onFailure = { t -> ReconcileRowUiState(failure = BackendMigrationResolver.renderFailure(failureReason(t))) },
            )
        }
    }

    fun runPantry() {
        pantry = pantry.copy(running = true, resultLines = null, failure = null)
        scope.launch {
            val client = SupabaseClientProvider.get(context)
            if (client == null) {
                pantry = pantry.copy(running = false, failure = BackendMigrationResolver.renderFailure("Supabase is not configured"))
                return@launch
            }
            val result = PantryReconcile.run(context, SupabasePantryBackend(client))
            pantry = result.fold(
                onSuccess = { report -> ReconcileRowUiState(resultLines = BackendMigrationResolver.renderPantryReport(report)) },
                onFailure = { t -> ReconcileRowUiState(failure = BackendMigrationResolver.renderFailure(failureReason(t))) },
            )
        }
    }

    fun runEvents() {
        events = events.copy(running = true, resultLines = null, failure = null)
        scope.launch {
            val client = SupabaseClientProvider.get(context)
            if (client == null) {
                events = events.copy(running = false, failure = BackendMigrationResolver.renderFailure("Supabase is not configured"))
                return@launch
            }
            val result = EventsReconcile.run(context, SupabaseEventsBackend(client))
            events = result.fold(
                onSuccess = { report -> ReconcileRowUiState(resultLines = BackendMigrationResolver.renderEventsReport(report)) },
                onFailure = { t -> ReconcileRowUiState(failure = BackendMigrationResolver.renderFailure(failureReason(t))) },
            )
        }
    }

    // Fleet is a PROJECTION, not a cutover (ticket 14's ruling) - the write path (waves 1-4) was
    // built and had zero callers. This makes it reachable so the export can actually run and be
    // diffed. It touches no fleet read: the phone keeps reading its own tables, and Drive keeps
    // syncing fleet between the two phones exactly as it does today.
    fun runFleet() {
        fleet = fleet.copy(running = true, resultLines = null, failure = null)
        scope.launch {
            val client = SupabaseClientProvider.get(context)
            if (client == null) {
                fleet = fleet.copy(running = false, failure = BackendMigrationResolver.renderFailure("Supabase is not configured"))
                return@launch
            }
            // eventsBackend passed explicitly (not the default NoOpEventsBackend) - the car_tasks
            // fold into `events` (backend-erp ticket 06/10) uploads through this seam, and this is
            // the one and only production route that upload ever runs through.
            val result = FleetReconcile.run(context, SupabaseFleetBackend(client), SupabaseEventsBackend(client))
            fleet = result.fold(
                onSuccess = { report -> ReconcileRowUiState(resultLines = BackendMigrationResolver.renderFleetReport(report)) },
                onFailure = { t -> ReconcileRowUiState(failure = BackendMigrationResolver.renderFailure(failureReason(t))) },
            )
        }
    }

    val readiness = BackendMigrationResolver.readiness(configured, membership)
    val disabledReason = BackendMigrationResolver.disabledReason(readiness, membership)

    BackendMigrationContent(
        state = BackendMigrationUiState(
            readiness = readiness,
            disabledReason = disabledReason,
            places = places,
            pantry = pantry,
            events = events,
            fleet = fleet,
        ),
        onRunPlaces = ::runPlaces,
        onRunPantry = ::runPantry,
        onRunEvents = ::runEvents,
        onRunFleet = ::runFleet,
        onBack = onBack,
    )
}

/** One row's transient state - never persisted, reset to running/blank on every tap. */
data class ReconcileRowUiState(
    val running: Boolean = false,
    /** Worded lines from [BackendMigrationResolver.renderPlacesReport] and its two siblings, or
     * null while nothing has completed yet. */
    val resultLines: List<String>? = null,
    /** [BackendMigrationResolver.renderFailure]'s output, or null on success/not-yet-run. */
    val failure: String? = null,
)

/** Everything [BackendMigrationContent] needs to render - no Context, no backend, no coroutine. */
data class BackendMigrationUiState(
    val readiness: BackendMigrationResolver.Readiness,
    val disabledReason: String?,
    val places: ReconcileRowUiState,
    val pantry: ReconcileRowUiState,
    val events: ReconcileRowUiState,
    val fleet: ReconcileRowUiState,
)

/** Plain state-plus-callbacks content - previewable with no Android services. */
@Composable
fun BackendMigrationContent(
    state: BackendMigrationUiState,
    onRunPlaces: () -> Unit,
    onRunPantry: () -> Unit,
    onRunEvents: () -> Unit,
    onRunFleet: () -> Unit,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val ready = state.readiness == BackendMigrationResolver.Readiness.READY

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Backend migration", onBack = onBack)
            Column(
                Modifier
                    .padding(horizontal = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "One-time (and re-runnable) uploads from this device's engine to your own " +
                        "Supabase project. Each button uploads that aspect's records, fills the " +
                        "on-device replica from what the server reports back, and changes " +
                        "nothing on the engine side - the engine stays the source of truth until " +
                        "a run comes back clean.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                if (!ready && state.disabledReason != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.disabledReason,
                        style = LegionType.stamp,
                        color = sem.estimated,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                BackendMigrationRow(
                    label = "Places",
                    description = "Uploads every saved place to your Supabase project and fills " +
                        "the on-device replica from it.",
                    enabled = ready,
                    disabledReason = if (ready) null else state.disabledReason,
                    row = state.places,
                    onRun = onRunPlaces,
                )

                Spacer(Modifier.height(16.dp))
                BackendMigrationRow(
                    label = "Pantry",
                    description = "Re-checks every stored receipt against its own arithmetic, " +
                        "uploads the ones that still reconcile, and fills the on-device replica " +
                        "from what the server has.",
                    enabled = ready,
                    disabledReason = if (ready) null else state.disabledReason,
                    row = state.pantry,
                    onRun = onRunPantry,
                )

                Spacer(Modifier.height(16.dp))
                BackendMigrationRow(
                    label = "Notes + Dates",
                    description = "Uploads every dated event and dated note item into one merged " +
                        "server table and fills the on-device replica from it. Undated note " +
                        "items are never uploaded - they stay on the engine.",
                    enabled = ready,
                    disabledReason = if (ready) null else state.disabledReason,
                    row = state.events,
                    onRun = onRunEvents,
                )

                Spacer(Modifier.height(16.dp))
                BackendMigrationRow(
                    label = "Fleet",
                    description = "Exports your fleet data to your Supabase project for the " +
                        "laptop surface and for durability. This is a PROJECTION, not a cutover - " +
                        "the phone keeps reading its own fleet tables and Drive keeps syncing " +
                        "fleet between your two phones, unchanged.",
                    enabled = ready,
                    disabledReason = if (ready) null else state.disabledReason,
                    row = state.fleet,
                    onRun = onRunFleet,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BackendMigrationRow(
    label: String,
    description: String,
    enabled: Boolean,
    disabledReason: String?,
    row: ReconcileRowUiState,
    onRun: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(description, style = LegionType.stamp, color = sem.faint)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRun, enabled = enabled && !row.running) {
                    Text(
                        if (row.running) "RUNNING" else "RUN",
                        style = LegionType.stamp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (!enabled && disabledReason != null) {
                Text(disabledReason, style = LegionType.stamp, color = sem.estimated)
            }

            row.failure?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = sem.quarantined)
            }

            row.resultLines?.let { lines ->
                Spacer(Modifier.height(4.dp))
                for (line in lines) {
                    Text(line, style = MaterialTheme.typography.bodySmall, color = sem.faint)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BackendMigrationContentNotConfiguredPreview() {
    LegionTheme {
        BackendMigrationContent(
            state = BackendMigrationUiState(
                readiness = BackendMigrationResolver.Readiness.NOT_CONFIGURED,
                disabledReason = BackendMigrationResolver.disabledReason(BackendMigrationResolver.Readiness.NOT_CONFIGURED, null),
                places = ReconcileRowUiState(),
                pantry = ReconcileRowUiState(),
                events = ReconcileRowUiState(),
                fleet = ReconcileRowUiState(),
            ),
            onRunPlaces = {},
            onRunPantry = {},
            onRunEvents = {},
            onRunFleet = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BackendMigrationContentReadyPreview() {
    LegionTheme {
        BackendMigrationContent(
            state = BackendMigrationUiState(
                readiness = BackendMigrationResolver.Readiness.READY,
                disabledReason = null,
                places = ReconcileRowUiState(
                    resultLines = listOf(
                        "Engine had 4 places; 4 uploaded.",
                        "Server now has 4 places; the on-device replica now has 4.",
                        "Clean - every place matches on both sides.",
                    ),
                ),
                pantry = ReconcileRowUiState(running = true),
                events = ReconcileRowUiState(
                    failure = BackendMigrationResolver.renderFailure("couldn't reach the server"),
                ),
                fleet = ReconcileRowUiState(),
            ),
            onRunPlaces = {},
            onRunPantry = {},
            onRunEvents = {},
            onRunFleet = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BackendMigrationContentOneSidedPreview() {
    LegionTheme {
        BackendMigrationContent(
            state = BackendMigrationUiState(
                readiness = BackendMigrationResolver.Readiness.READY,
                disabledReason = null,
                places = ReconcileRowUiState(
                    resultLines = listOf(
                        "Engine had 3 places; 3 uploaded.",
                        "Server now has 4 places; the on-device replica now has 4.",
                        "Only on the server, not on this device: garage.",
                        "Not clean yet - see the lines above.",
                    ),
                ),
                pantry = ReconcileRowUiState(),
                events = ReconcileRowUiState(),
                fleet = ReconcileRowUiState(
                    resultLines = listOf(
                        "This is a one-way export to your own Supabase project, for the laptop " +
                            "surface and for durability - it is not a cutover. The phone keeps " +
                            "reading its own fleet tables, and Drive keeps syncing fleet between " +
                            "your two phones, unchanged by anything below.",
                        "Overall: NOT clean - see the tables below.",
                        "Vehicles: 2 on this device, already all on the server. Clean.",
                        "Service history: 5 on this device, 1 uploaded this run. Clean.",
                        "Drives: 12 on this device, already all on the server. NOT clean. Only on the server: sync-9f2.",
                    ),
                ),
            ),
            onRunPlaces = {},
            onRunPantry = {},
            onRunEvents = {},
            onRunFleet = {},
            onBack = {},
        )
    }
}
