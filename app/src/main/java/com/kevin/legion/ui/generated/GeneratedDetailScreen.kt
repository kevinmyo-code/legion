package com.kevin.legion.ui.generated

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.DeckSectionRule
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import org.json.JSONObject

/**
 * The generated DETAIL screen (aspect-engine ticket 10 answer point 3, built by ticket 18): every
 * field (including [FieldType.COMPUTED], its own [com.kevin.legion.engine.ComputedValue.Error]
 * rendered in words rather than swallowed - CLAUDE.md §4 rule 6 read onto a generated surface),
 * provenance IN WORDS (never colour alone - ticket 10's own "sec 4 rule 7 applies to generated
 * surfaces exactly as to hand-built ones"), and child records reached via [FieldType.REFERENCE].
 *
 * **The plugin escape hatch (ticket 10 answer point 4, ADR 0035).** [pluginOverride], when non-null,
 * renders INSTEAD of everything below and this function returns immediately after - the generated
 * screen below it is never built or composed in that case, so a plugin paying for a native detail
 * screen (a car with live OBD) pays nothing extra for the fallback it is not using. `null` (the
 * default) is every record type that has not opted into a plugin-provided detail yet, which is
 * every record type as of this ticket - nothing has migrated onto the engine (CLAUDE.md §10), so
 * this parameter exists and is exercised by [GeneratedScreensTest] but has no real caller passing a
 * non-null value yet.
 */
@Composable
fun GeneratedDetailScreen(
    recordId: Long,
    onBack: () -> Unit,
    onOpenChildRecord: (Long) -> Unit,
    onEdit: () -> Unit,
    pluginOverride: (@Composable (recordId: Long) -> Unit)? = null,
) {
    if (pluginOverride != null) {
        pluginOverride(recordId)
        return
    }

    val context = LocalContext.current
    val db = remember { CarDatabase.getDatabase(context) }
    val sem = LocalLegionSemantics.current

    var record by remember(recordId) { mutableStateOf<EngineRecord?>(null) }
    var fieldDefs by remember(recordId) { mutableStateOf<List<FieldDef>>(emptyList()) }
    var children by remember(recordId) { mutableStateOf<List<Pair<FieldDef, List<EngineRecord>>>>(emptyList()) }
    var loaded by remember(recordId) { mutableStateOf(false) }

    LaunchedEffect(recordId) {
        val r = db.engineRecordDao().getById(recordId)
        record = r
        if (r != null) {
            fieldDefs = db.fieldDefDao().forRecordType(r.recordTypeId)
            // Child records via references (ticket 10 answer point 3) - every REFERENCE field
            // anywhere in the schema whose target is THIS record's own type, then every active
            // record of that field's owning type that actually points at this one.
            val referenceFields = db.fieldDefDao().allReferenceFields()
                .filter { FieldConfig.referenceConfig(it.config)?.targetRecordTypeId == r.recordTypeId }
            children = referenceFields.map { refField ->
                val referrers = db.engineRecordDao().activeByRecordType(refField.recordTypeId)
                    .filter { PayloadCodec.readReferenceId(JSONObject(it.payload), refField.id) == recordId }
                refField to referrers
            }.filter { it.second.isNotEmpty() }
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize()) {
        DeckScreenHeader(title = "RECORD", onBack = onBack)
        val r = record
        if (!loaded) {
            Text("LOADING", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(12.dp))
            return@Column
        }
        if (r == null) {
            Text("RECORD NOT FOUND", style = LegionType.stamp, color = sem.quarantined, modifier = Modifier.padding(12.dp))
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            DeckPane(header = "DETAIL", headerAccent = provenanceWord(r.provenance)) {
                val payload = JSONObject(r.payload)
                fieldDefs.forEach { fd ->
                    val (value, isError) = renderFieldValue(fd, payload)
                    DeckRow(label = fd.name, value = value ?: "-")
                    if (isError) {
                        Text("  ${fd.name}: computed field error - $value", style = LegionType.stamp, color = sem.quarantined)
                    }
                }
            }
            DeckButton(text = "EDIT", onClick = onEdit, modifier = Modifier.padding(top = 12.dp))
            if (children.isNotEmpty()) {
                DeckSectionRule(label = "LINKED RECORDS")
                children.forEach { (refField, referrers) ->
                    Text("VIA '${refField.name}'", style = LegionType.stamp, color = sem.faint)
                    referrers.forEach { child ->
                        DeckRow(
                            label = "Record #${child.id}",
                            value = "",
                            modifier = Modifier.clickable { onOpenChildRecord(child.id) },
                        )
                    }
                }
            }
        }
    }
}

/** "in words" per CLAUDE.md §4 rule 7 - never a colour swatch alone. */
private fun provenanceWord(provenance: RecordProvenance): String = when (provenance) {
    RecordProvenance.DETERMINISTIC -> "DETERMINISTIC"
    RecordProvenance.LLM_RECONCILED -> "LLM RECONCILED"
    RecordProvenance.UNRECONCILED -> "UNRECONCILED - NOT VERIFIED"
    RecordProvenance.USER -> "HAND-ENTERED"
}

/** @return the display string, plus whether it represents a [com.kevin.legion.engine.ComputedValue.Error]
 * (CLAUDE.md §4 rule 6: a computed error is never swallowed or shown as a bare dash). */
private fun renderFieldValue(fd: FieldDef, payload: JSONObject): Pair<String?, Boolean> {
    if (fd.type == FieldType.COMPUTED) {
        val err = PayloadCodec.readComputedError(payload, fd.id)
        if (err != null) return err to true
    }
    val value = when (fd.type) {
        FieldType.MONEY_CENTS -> PayloadCodec.readLong(payload, fd.id)?.let { cents ->
            val sign = if (cents < 0) "-" else ""
            val abs = kotlin.math.abs(cents)
            "$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        }
        FieldType.NUMBER, FieldType.RATING, FieldType.COMPUTED -> PayloadCodec.readDouble(payload, fd.id)?.toString()
        FieldType.DATE, FieldType.DATETIME -> PayloadCodec.readLong(payload, fd.id)
            ?.let { java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(it)) }
        FieldType.BOOLEAN -> payload.opt(PayloadCodec.key(fd.id))?.takeIf { it != JSONObject.NULL }?.let { if (it == true) "YES" else "NO" }
        FieldType.REFERENCE -> PayloadCodec.readReferenceId(payload, fd.id)?.let { "Record #$it" }
        else -> PayloadCodec.readString(payload, fd.id)
    }
    return value to false
}
