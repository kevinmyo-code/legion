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
import com.kevin.legion.backend.ConversationAuditReconcile
import com.kevin.legion.backend.FleetReconcile
import com.kevin.legion.backend.MembershipResult
import com.kevin.legion.backend.ObdSampleReconcile
import com.kevin.legion.backend.PantryReconcile
import com.kevin.legion.backend.PlacesReconcile
import com.kevin.legion.backend.SupabaseAuth
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabaseConfig
import com.kevin.legion.backend.SupabaseConversationAuditBackend
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
 * `settings/backend-migration` - the hands path for backend-erp Phase 4's reconciles
 * ([PlacesReconcile]/[PantryReconcile], and originally a since-retired `EventsReconcile` - see this
 * file's own "Notes + Dates" removal note below), which as of Phase 4's own ticket had zero
 * production callers: fully built, tested, and unreachable. Reachable from
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
    var fleet by remember { mutableStateOf(ReconcileRowUiState()) }
    var obdSamples by remember { mutableStateOf(ReconcileRowUiState()) }
    var conversationAudit by remember { mutableStateOf(ReconcileRowUiState()) }

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

    // runEvents()/the "Notes + Dates" row REMOVED 2026-09-02 (live-sync ticket 04) along with
    // EventsReconcile itself - EventsSync/EventsRealtime/EventsOutbox now sync that aspect live,
    // automatically, on every foreground and on a Realtime push, so there is nothing left for a
    // manual Settings button to do. See `memory/library/decisions.md`'s 2026-09-02 entry for why
    // the old button was retired as dangerous (it hid 120 real coursework rows and its retraction
    // guard soft-deleted ~130 rows on a routine re-check) rather than merely made redundant.

    // **CORRECTED 2026-08-29. This copy described the world for one day and then outlived it.**
    // It used to say fleet was a PROJECTION rather than a cutover, that the phone kept reading its
    // own tables, and that Drive kept syncing fleet between the two phones "exactly as it does
    // today". Ticket 14 was REVERSED the next day: fleet writes go to Supabase on the ordinary
    // path, and every cut-over table left the `SyncEngine` registry, so the Drive clause is false
    // too. All three clauses were wrong at once, on a screen a person reads before deciding whether
    // to tap something that writes to a live server.
    //
    // Third time this session that a comment or a label promised what the code no longer did -
    // `SyncEngine`'s registry claimed `MirrorSync` was live, `DeviceId` claimed its value never
    // leaves the device. Worth noticing that all three were about a change that happened LATER than
    // the text, which is the only kind of stale that a careful author cannot catch at writing time.
    // The defence is re-reading the copy next to the diff that falsifies it, not writing it better.
    //
    // What runFleet does now: it is a catch-up for rows that predate the cutover. The normal path
    // is `FleetEngineStore`, which writes through on every edit.
    fun runFleet() {
        fleet = fleet.copy(running = true, resultLines = null, failure = null)
        scope.launch {
            val client = SupabaseClientProvider.get(context)
            if (client == null) {
                fleet = fleet.copy(running = false, failure = BackendMigrationResolver.renderFailure("Supabase is not configured"))
                return@launch
            }
            val result = FleetReconcile.run(context, SupabaseFleetBackend(client))
            fleet = result.fold(
                onSuccess = { report -> ReconcileRowUiState(resultLines = BackendMigrationResolver.renderFleetReport(report)) },
                onFailure = { t -> ReconcileRowUiState(failure = BackendMigrationResolver.renderFailure(failureReason(t))) },
            )
        }
    }

    // The last two phone-only tables (ticket 14's obd_samples question, ticket 24's conversation
    // audit ruling, both 2026-08-29). Separate rows from Fleet's own, not folded into runFleet -
    // see ObdSampleReconcile's own class doc for why it is a sibling object, not a thirteenth wave.
    fun runObdSamples() {
        obdSamples = obdSamples.copy(running = true, resultLines = null, failure = null)
        scope.launch {
            val client = SupabaseClientProvider.get(context)
            if (client == null) {
                obdSamples = obdSamples.copy(running = false, failure = BackendMigrationResolver.renderFailure("Supabase is not configured"))
                return@launch
            }
            val result = ObdSampleReconcile.run(context, SupabaseFleetBackend(client))
            obdSamples = result.fold(
                onSuccess = { report -> ReconcileRowUiState(resultLines = BackendMigrationResolver.renderObdSampleReport(report)) },
                onFailure = { t -> ReconcileRowUiState(failure = BackendMigrationResolver.renderFailure(failureReason(t))) },
            )
        }
    }

    fun runConversationAudit() {
        conversationAudit = conversationAudit.copy(running = true, resultLines = null, failure = null)
        scope.launch {
            val client = SupabaseClientProvider.get(context)
            if (client == null) {
                conversationAudit = conversationAudit.copy(running = false, failure = BackendMigrationResolver.renderFailure("Supabase is not configured"))
                return@launch
            }
            val result = ConversationAuditReconcile.run(context, SupabaseConversationAuditBackend(client))
            conversationAudit = result.fold(
                onSuccess = { report -> ReconcileRowUiState(resultLines = BackendMigrationResolver.renderConversationAuditReport(report)) },
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
            fleet = fleet,
            obdSamples = obdSamples,
            conversationAudit = conversationAudit,
        ),
        onRunPlaces = ::runPlaces,
        onRunPantry = ::runPantry,
        onRunFleet = ::runFleet,
        onRunObdSamples = ::runObdSamples,
        onRunConversationAudit = ::runConversationAudit,
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
    val fleet: ReconcileRowUiState,
    val obdSamples: ReconcileRowUiState,
    val conversationAudit: ReconcileRowUiState,
)

/** Plain state-plus-callbacks content - previewable with no Android services. */
@Composable
fun BackendMigrationContent(
    state: BackendMigrationUiState,
    onRunPlaces: () -> Unit,
    onRunPantry: () -> Unit,
    onRunFleet: () -> Unit,
    onRunObdSamples: () -> Unit,
    onRunConversationAudit: () -> Unit,
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

                // "Notes + Dates" row REMOVED 2026-09-02 (live-sync ticket 04) - see the class doc
                // and runFleet's own preceding comment block for the account of why.

                Spacer(Modifier.height(16.dp))
                BackendMigrationRow(
                    label = "Fleet",
                    description = "Uploads any fleet rows that have not reached your Supabase " +
                        "project yet - vehicles, service history, drives, codes, specs and build " +
                        "entries. Fleet writes go to the server as they happen now, so this is a " +
                        "catch-up for anything from before the cutover, not the normal path.",
                    enabled = ready,
                    disabledReason = if (ready) null else state.disabledReason,
                    row = state.fleet,
                    onRun = onRunFleet,
                )

                Spacer(Modifier.height(16.dp))
                BackendMigrationRow(
                    label = "OBD telemetry",
                    description = "Uploads new OBD samples to your Supabase project in batches, " +
                        "resuming from where the last run left off. The last of fleet's tables to " +
                        "reach the server.",
                    enabled = ready,
                    disabledReason = if (ready) null else state.disabledReason,
                    row = state.obdSamples,
                    onRun = onRunObdSamples,
                )

                Spacer(Modifier.height(16.dp))
                BackendMigrationRow(
                    label = "Conversation audit",
                    description = "Uploads your conversation-and-tool-call audit trail to your " +
                        "Supabase project, resuming from where the last run left off. " +
                        "Read-through redaction already happened on this device before any of " +
                        "it was ever stored.",
                    enabled = ready,
                    disabledReason = if (ready) null else state.disabledReason,
                    row = state.conversationAudit,
                    onRun = onRunConversationAudit,
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
                fleet = ReconcileRowUiState(),
                obdSamples = ReconcileRowUiState(),
                conversationAudit = ReconcileRowUiState(),
            ),
            onRunPlaces = {},
            onRunPantry = {},
            onRunFleet = {},
            onRunObdSamples = {},
            onRunConversationAudit = {},
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
                fleet = ReconcileRowUiState(),
                obdSamples = ReconcileRowUiState(),
                conversationAudit = ReconcileRowUiState(),
            ),
            onRunPlaces = {},
            onRunPantry = {},
            onRunFleet = {},
            onRunObdSamples = {},
            onRunConversationAudit = {},
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
                obdSamples = ReconcileRowUiState(
                    resultLines = listOf(
                        "This uploads new OBD telemetry to your own Supabase project in batches, " +
                            "resuming from where the last run left off - it does not re-scan or " +
                            "re-check the whole table every time.",
                        "26059 samples on this device; 412 uploaded this run.",
                    ),
                ),
                conversationAudit = ReconcileRowUiState(
                    resultLines = listOf(
                        "This uploads your conversation-and-tool-call audit trail to your own " +
                            "Supabase project, resuming from where the last run left off. " +
                            "Read-through redaction already happened on this device before any " +
                            "of it was ever stored, so nothing sent here is content this app " +
                            "promised to keep off a server.",
                        "197 rows on this device; 3 uploaded this run.",
                    ),
                ),
            ),
            onRunPlaces = {},
            onRunPantry = {},
            onRunFleet = {},
            onRunObdSamples = {},
            onRunConversationAudit = {},
            onBack = {},
        )
    }
}
