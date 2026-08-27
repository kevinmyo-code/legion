package com.kevin.legion.ui.generated

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.EnginePhotoStore
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.engine.GeneratedFormValidation
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.ui.rememberCameraCaptureLauncher
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckCheckbox
import com.kevin.legion.ui.common.DeckRadio
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.DeckSwitch
import com.kevin.legion.ui.common.DeckTextField
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The generated ADD/EDIT form (aspect-engine ticket 10 answer point 2, built by ticket 18): one
 * editor per [FieldDef.type], required-field enforcement, and quarantine-style failure messaging in
 * words when the underlying [RecordStore] write is refused. [recordId] null = add, non-null = edit
 * an existing record's current values (prefilled from its payload).
 *
 * **Validation is two-layer, by design** (see [GeneratedFormValidation]'s own doc): the pure,
 * offline check runs first and blocks a submit outright with per-field messages; only a form that
 * passes it reaches [RecordStore.create]/[RecordStore.update], whose own [RecordStore.WriteResult.Failure]
 * (a stale reference, a deleted field) is shown the same way - a plain worded banner, never a raw
 * exception, never a silent no-op.
 */
@Composable
fun GeneratedFormScreen(
    recordTypeId: Long,
    recordId: Long?,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
) {
    val context = LocalContext.current
    val db = remember { CarDatabase.getDatabase(context) }
    val store = remember { RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao()) }
    val scope = rememberCoroutineScope()
    val sem = LocalLegionSemantics.current

    var fieldDefs by remember(recordTypeId) { mutableStateOf<List<FieldDef>>(emptyList()) }
    var loaded by remember(recordTypeId, recordId) { mutableStateOf(false) }
    val values = remember(recordTypeId, recordId) { mutableStateMapOf<Long, Any?>() }
    var fieldErrors by remember { mutableStateOf<List<GeneratedFormValidation.FieldError>>(emptyList()) }
    var writeFailure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(recordTypeId, recordId) {
        val defs = db.fieldDefDao().forRecordType(recordTypeId)
        fieldDefs = defs
        if (recordId != null) {
            val existing = db.engineRecordDao().getById(recordId)
            if (existing != null) {
                val payload = JSONObject(existing.payload)
                for (fd in defs) {
                    if (fd.type == FieldType.COMPUTED) continue
                    values[fd.id] = readTyped(fd, payload)
                }
            }
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize()) {
        DeckScreenHeader(title = if (recordId == null) "ADD RECORD" else "EDIT RECORD", onBack = onBack)
        if (!loaded) {
            Text("LOADING", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(12.dp))
            return@Column
        }
        Column(Modifier.fillMaxSize().weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            if (writeFailure != null) {
                Text("COULD NOT SAVE: $writeFailure", style = LegionType.stamp, color = sem.quarantined, modifier = Modifier.padding(bottom = 8.dp))
            }
            fieldDefs.forEach { fd ->
                if (fd.type == FieldType.COMPUTED) return@forEach // never user-editable - RecordStore materializes it
                val error = fieldErrors.firstOrNull { it.fieldId == fd.id }
                FieldEditor(fd = fd, values = values, db = db)
                if (error != null) {
                    Text(error.message.uppercase(), style = LegionType.stamp, color = sem.quarantined, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
        DeckButton(
            text = if (saving) "SAVING" else "SAVE",
            enabled = !saving,
            onClick = {
                val errors = GeneratedFormValidation.validate(fieldDefs, values)
                fieldErrors = errors
                if (errors.isNotEmpty()) return@DeckButton
                saving = true
                scope.launch {
                    val result = if (recordId == null) {
                        store.create(recordTypeId, values.toMap(), RecordProvenance.USER)
                    } else {
                        store.update(recordId, values.toMap())
                    }
                    saving = false
                    when (result) {
                        is RecordStore.WriteResult.Success -> {
                            writeFailure = null
                            onSaved(result.recordId)
                        }
                        is RecordStore.WriteResult.Failure -> writeFailure = result.reason
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
    }
}

@Composable
private fun FieldEditor(fd: FieldDef, values: androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Any?>, db: CarDatabase) {
    val sem = LocalLegionSemantics.current
    val label = fd.name + if (fd.required) " *" else ""
    when (fd.type) {
        FieldType.TEXT, FieldType.LOCATION -> {
            var text by remember(fd.id) { mutableStateOf(values[fd.id] as? String ?: "") }
            DeckTextField(
                value = text,
                onValueChange = { text = it; values[fd.id] = it },
                label = label,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        FieldType.NUMBER, FieldType.RATING -> {
            var text by remember(fd.id) { mutableStateOf((values[fd.id] as? Double)?.toString() ?: "") }
            DeckTextField(
                value = text,
                onValueChange = { text = it; values[fd.id] = it.toDoubleOrNull() },
                label = label,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        FieldType.MONEY_CENTS -> {
            var text by remember(fd.id) { mutableStateOf((values[fd.id] as? Long)?.let { "%.2f".format(it / 100.0) } ?: "") }
            DeckTextField(
                value = text,
                onValueChange = { input ->
                    text = input
                    val dollars = input.toDoubleOrNull()
                    values[fd.id] = dollars?.let { Math.round(it * 100.0) }
                },
                label = "$label (dollars)",
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        FieldType.DATE, FieldType.DATETIME -> {
            var text by remember(fd.id) { mutableStateOf((values[fd.id] as? Long)?.toString() ?: "") }
            Column(Modifier.padding(bottom = 8.dp)) {
                DeckTextField(
                    value = text,
                    onValueChange = { text = it; values[fd.id] = it.toLongOrNull() },
                    label = "$label (epoch ms)",
                    modifier = Modifier.fillMaxWidth(),
                )
                DeckButton(text = "NOW", onClick = { val now = System.currentTimeMillis(); text = now.toString(); values[fd.id] = now })
            }
        }
        FieldType.BOOLEAN -> {
            val checked = values[fd.id] as? Boolean ?: false
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(label.uppercase(), style = LegionType.stamp, color = sem.faint)
                DeckSwitch(checked = checked, onCheckedChange = { values[fd.id] = it })
            }
        }
        FieldType.CHOICE -> {
            val options = FieldConfig.choiceOptions(fd.config)
            val selected = values[fd.id] as? String
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(label.uppercase(), style = LegionType.stamp, color = sem.faint)
                if (options.isEmpty()) {
                    Text("NO OPTIONS CONFIGURED", style = LegionType.stamp, color = sem.quarantined)
                } else {
                    options.forEach { opt ->
                        DeckRadio(selected = selected == opt, onClick = { values[fd.id] = opt }, label = opt)
                    }
                }
            }
        }
        FieldType.MULTI_SELECT_CHOICE -> {
            val options = FieldConfig.choiceOptions(fd.config)
            @Suppress("UNCHECKED_CAST")
            val selected = (values[fd.id] as? List<String>) ?: emptyList()
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(label.uppercase(), style = LegionType.stamp, color = sem.faint)
                if (options.isEmpty()) {
                    Text("NO OPTIONS CONFIGURED", style = LegionType.stamp, color = sem.quarantined)
                } else {
                    options.forEach { opt ->
                        DeckCheckbox(
                            checked = opt in selected,
                            onCheckedChange = { checked ->
                                values[fd.id] = if (checked) selected + opt else selected - opt
                            },
                            label = opt,
                        )
                    }
                }
            }
        }
        FieldType.REFERENCE -> {
            val refConfig = FieldConfig.referenceConfig(fd.config)
            val selected = values[fd.id] as? Long
            var candidates by remember(fd.id) { mutableStateOf<List<EngineRecord>>(emptyList()) }
            LaunchedEffect(fd.id) {
                candidates = refConfig?.let { db.engineRecordDao().activeByRecordType(it.targetRecordTypeId) } ?: emptyList()
            }
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(label.uppercase(), style = LegionType.stamp, color = sem.faint)
                if (refConfig == null) {
                    Text("NOT CONFIGURED", style = LegionType.stamp, color = sem.quarantined)
                } else if (candidates.isEmpty()) {
                    Text("NO CANDIDATE RECORDS YET", style = LegionType.stamp, color = sem.faint)
                } else {
                    candidates.forEach { candidate ->
                        DeckRadio(
                            selected = selected == candidate.id,
                            onClick = { values[fd.id] = candidate.id },
                            label = "Record #${candidate.id}",
                        )
                    }
                }
            }
        }
        FieldType.PHOTO -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            val currentPath = values[fd.id] as? String
            var bitmap by remember(fd.id) { mutableStateOf<Bitmap?>(null) }
            val launch = rememberCameraCaptureLauncher(context) { captured ->
                bitmap = captured
                values[fd.id] = EnginePhotoStore.save(context, captured)
            }
            // A freshly-captured bitmap is trusted outright (it was just written this composition),
            // but an already-stored path is not: `.scratch/backend-erp/issues/09-backups-do-not-
            // cover-files.md` is exactly the case where the record survives (a database restore)
            // and the file underneath its path does not (backups never covered `files/`). Checking
            // the disk here, rather than trusting a non-null path, is what tells "PHOTO ON FILE"
            // apart from "PHOTO MISSING" instead of asserting the former from the string alone.
            val photoStatus = if (bitmap == null) {
                PhotoFieldResolver.status(currentPath) { path -> java.io.File(path).exists() }
            } else {
                null
            }
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(label.uppercase(), style = LegionType.stamp, color = sem.faint)
                if (bitmap != null) {
                    Image(bitmap!!.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(96.dp))
                } else if (photoStatus != null) {
                    PhotoFieldResolver.label(photoStatus)?.let {
                        // MISSING reads as a genuine problem (the quarantine tone), never the same
                        // faint weight as the routine ON_FILE state - CLAUDE.md's "unreadable and
                        // empty are different sentences" rule needs a different colour, not just
                        // different words, or a driver skimming past faint text would miss it.
                        Text(it, style = LegionType.stamp, color = if (photoStatus == PhotoFieldResolver.Status.MISSING) sem.quarantined else sem.faint)
                    }
                }
                DeckButton(text = "TAKE PHOTO", onClick = launch)
            }
        }
        FieldType.COMPUTED -> Unit // filtered out by the caller; unreachable
    }
}

/** Reads [payload]'s stored value for [fd] back into the same Kotlin runtime type
 * [GeneratedFormValidation.shapeMatches] and [PayloadCodec.write] agree on - the prefill half of
 * edit mode. */
private fun readTyped(fd: FieldDef, payload: JSONObject): Any? = when (fd.type) {
    FieldType.MONEY_CENTS, FieldType.REFERENCE, FieldType.DATE, FieldType.DATETIME -> PayloadCodec.readLong(payload, fd.id)
    FieldType.NUMBER, FieldType.RATING -> PayloadCodec.readDouble(payload, fd.id)
    FieldType.BOOLEAN -> payload.opt(PayloadCodec.key(fd.id))?.takeIf { it != JSONObject.NULL } as? Boolean
    FieldType.MULTI_SELECT_CHOICE -> {
        val arr = payload.optJSONArray(PayloadCodec.key(fd.id))
        if (arr == null) null else (0 until arr.length()).map { arr.getString(it) }
    }
    else -> PayloadCodec.readString(payload, fd.id)
}
