package com.kevin.legion.ui.fleet

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CodeClearEvent
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.util.Temp
import com.kevin.legion.util.TempUnit
import com.kevin.legion.vehicle.CarToolbelt
import com.kevin.legion.vehicle.DtcDescriptions
import com.kevin.legion.vehicle.DtcLookup
import com.kevin.legion.vehicle.DtcLookupState
import com.kevin.legion.vehicle.DtcMeaning
import com.kevin.legion.vehicle.DtcMeanings
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.FreezeFrame
import com.kevin.legion.vehicle.FreezeFrameField
import com.kevin.legion.vehicle.VehicleController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One code event on the codes card. [fields] empty means no freeze frame was saved. */
data class CodeCardRow(
    val eventId: Long,
    /** Each code with what the card shows for it, source included (standard, AI, or not yet known). */
    val codes: List<Pair<String, DtcMeaning>>,
    val whenText: String,
    /** "About 187,400 mi (estimated)", or null when no mileage was recorded. */
    val mileageText: String?,
    val cleared: Boolean,
    val fields: List<FreezeFrameField>,
)

/** What the codes card shows: one car's code events, newest first. */
data class CodesCardState(val vehicleLabel: String, val rows: List<CodeCardRow>)

private val WHEN_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a")

/**
 * [CodesCardState] from stored rows. Pure, so the card renders in a test with no Room. The cleared
 * test is the Faults page's own ([clearedMarkerFor]); the freeze frame goes through the one
 * formatter the voice tools use ([FreezeFrame.fields]), so card and speech cannot disagree.
 */
internal fun codesCardState(
    vehicleLabel: String,
    events: List<CodeEvent>,
    clears: List<CodeClearEvent>,
    seed: Map<String, Pair<String, String>>,
    learned: Map<String, Pair<String, String>>,
    tempUnit: TempUnit,
    zone: ZoneId = ZoneId.systemDefault(),
    failed: Set<String> = emptySet(),
): CodesCardState = CodesCardState(
    vehicleLabel,
    events.sortedByDescending { it.timestamp }.map { e ->
        CodeCardRow(
            eventId = e.id,
            codes = CarToolbelt.codeList(e.codesJson).map { it to DtcMeanings.resolve(it, seed, learned, failed) },
            whenText = Instant.ofEpochMilli(e.timestamp).atZone(zone).format(WHEN_FORMAT),
            mileageText = e.mileage?.let { "About ${"%,d".format(it)} mi (estimated)" },
            cleared = clearedMarkerFor(e, clears) != null,
            fields = FreezeFrame.fields(e.freezeFrameJson, tempUnit),
        )
    },
)

/**
 * The codes card (2026-11-03, Kevin: "i need a visual to pop up when i ask too, codes and freeze
 * frames etc"). Lists each code event (codes and meanings, when, mileage, cleared or not) and the
 * selected event's freeze frame as a parameter / value / unit table. Tapping an event switches the
 * snapshot. Stateless: [selectedId] null selects the newest. Closable by [onClose], and by the
 * hosting sheet's scrim, swipe and back.
 */
@Composable
fun CodesCard(
    state: CodesCardState,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sem = LocalLegionSemantics.current
    val selected = state.rows.firstOrNull { it.eventId == selectedId } ?: state.rows.firstOrNull()
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Trouble codes", style = MaterialTheme.typography.titleMedium)
                Text(state.vehicleLabel, style = MaterialTheme.typography.bodySmall, color = sem.faint)
            }
            IconButton(onClick = onClose) {
                MsIcon(R.drawable.ms_close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        if (state.rows.isEmpty()) {
            Text(
                "No trouble codes are recorded for this car.",
                style = MaterialTheme.typography.bodyMedium,
                color = sem.faint,
            )
            return@Column
        }
        Column(
            Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in state.rows) {
                CodeEventChip(row, isSelected = row.eventId == selected?.eventId, onClick = { onSelect(row.eventId) })
            }
        }
        if (selected != null) {
            Spacer(Modifier.padding(top = 16.dp))
            Text(
                "Freeze frame, ${selected.codes.joinToString(", ") { it.first }}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.padding(top = 4.dp))
            if (selected.fields.isEmpty()) {
                Text(FreezeFrame.NO_SNAPSHOT, style = MaterialTheme.typography.bodyMedium, color = sem.faint)
            } else {
                FreezeFrameTable(selected.fields)
            }
        }
    }
}

@Composable
private fun CodeEventChip(row: CodeCardRow, isSelected: Boolean, onClick: () -> Unit) {
    val sem = LocalLegionSemantics.current
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier.fillMaxWidth()
            .background(if (isSelected) SoftColors.cardHighest else SoftColors.card, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { selected = isSelected }
            .padding(12.dp),
    ) {
        for ((code, meaning) in row.codes) {
            Row {
                Text(
                    code,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    val known = meaning is DtcMeaning.Standard || meaning is DtcMeaning.AiExplanation
                    Text(
                        DtcMeanings.headline(meaning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (known) MaterialTheme.colorScheme.onSurface else sem.faint,
                    )
                    for (note in DtcMeanings.notes(code, meaning)) {
                        Text(note, style = MaterialTheme.typography.bodySmall, color = sem.faint)
                    }
                }
            }
        }
        Text(
            listOfNotNull(row.whenText, row.mileageText, if (row.cleared) "Cleared since" else "Not cleared since")
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = sem.faint,
        )
        if (row.fields.isEmpty()) {
            Text("No snapshot", style = MaterialTheme.typography.bodySmall, color = sem.faint)
        }
    }
}

private const val NAME_WEIGHT = 1.6f
private const val UNIT_WEIGHT = 0.6f

@Composable
private fun FreezeFrameTable(fields: List<FreezeFrameField>) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 4.dp)) {
            Text(
                "Parameter",
                Modifier.weight(NAME_WEIGHT),
                style = MaterialTheme.typography.labelMedium,
                color = sem.faint,
            )
            Text("Value", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = sem.faint)
            Text("Unit", Modifier.weight(UNIT_WEIGHT), style = MaterialTheme.typography.labelMedium, color = sem.faint)
        }
        for (f in fields) {
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(f.name, Modifier.weight(NAME_WEIGHT), style = MaterialTheme.typography.bodyMedium)
                Text(
                    f.value,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    f.unit,
                    Modifier.weight(UNIT_WEIGHT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = sem.faint,
                )
            }
        }
    }
}

/** What the card is built from, read once from Room and assets. [vehicleName] is blank when unknown. */
internal data class CodesCardInputs(
    val vehicleName: String,
    val events: List<CodeEvent>,
    val clears: List<CodeClearEvent>,
    val seed: Map<String, Pair<String, String>>,
    val learned: Map<String, Pair<String, String>>,
    val tempUnit: TempUnit,
) {
    /** Codes on the card that neither the seed nor the learned cache describes. */
    fun unknownCodes(): Set<String> =
        events.flatMap { CarToolbelt.codeList(it.codesJson) }.filter { it !in seed && it !in learned }.toSet()

    fun state(lookup: DtcLookupState): CodesCardState = codesCardState(
        vehicleLabel = vehicleName.ifBlank { "This car" },
        events = events,
        clears = clears,
        seed = seed,
        learned = learned + lookup.learned,
        tempUnit = tempUnit,
        failed = lookup.failed,
    )
}

/** Reads one car's code events and the dictionaries. The only Room touch for the card. */
internal suspend fun loadCodesCard(context: Context, vehicleId: String): CodesCardInputs = withContext(Dispatchers.IO) {
    val db = CarDatabase.getDatabase(context)
    val vehicle = FleetEngineStore.getByMac(context, vehicleId)
    CodesCardInputs(
        vehicleName = vehicle?.let { VehicleController.label(it) }.orEmpty(),
        events = db.codeEventDao().getAll(vehicleId),
        clears = db.codeClearEventDao().getAll(vehicleId),
        seed = DtcDescriptions.loadSeed(context),
        learned = DtcDescriptions.loadLearned(context),
        tempUnit = Temp.unit(context),
    )
}

/**
 * The codes card with its loader, as [com.kevin.legion.ui.VoiceModalHost] and the Fleet tab's
 * Faults page both host it: the same composable on the voice path and the hands path (ADR 0035).
 *
 * A code neither dictionary knows shows "Looking this up..." while [DtcLookup] asks the diagnostics
 * sub-agent in the background (2026-11-03); the answer is saved to the learned cache and shown with
 * the AI label, and a failed lookup reads "No description yet". [lookup] is a parameter so a test
 * can hand in a fake.
 */
@Composable
fun CodesCardHost(vehicleId: String, onClose: () -> Unit, lookup: DtcLookup? = null) {
    val context = LocalContext.current
    val dtcLookup = lookup ?: remember { DtcLookup.get(context) }
    var inputs by remember(vehicleId) { mutableStateOf<CodesCardInputs?>(null) }
    var selectedId by remember(vehicleId) { mutableStateOf<Long?>(null) }
    val lookupState by dtcLookup.state.collectAsState()
    LaunchedEffect(vehicleId) {
        val loaded = loadCodesCard(context, vehicleId)
        inputs = loaded
        dtcLookup.request(loaded.vehicleName, loaded.unknownCodes())
    }
    SoftTheme {
        val s = inputs?.let { remember(it, lookupState) { it.state(lookupState) } }
        if (s == null) {
            Text(
                "Loading codes...",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalLegionSemantics.current.faint,
            )
        } else {
            CodesCard(s, selectedId, onSelect = { selectedId = it }, onClose = onClose)
        }
    }
}
