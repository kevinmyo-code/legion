package com.kevin.legion.engine.mirror

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.engine.GeneratedFormValidation
import com.kevin.legion.engine.RecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates the xlsx mirror's export/import (ticket 20 items 2/3/4, on tickets 12/13's binding
 * answers). This is the ONE place that turns [com.kevin.legion.engine.RecordStore] data into
 * [MirrorCodec] bytes and back - [MirrorCodec] itself never touches Room or SAF, [MirrorStore]
 * never touches Room, so this class is where the three meet.
 *
 * **Export cadence** (ticket 20 item 2): [scheduleExport] debounces - call it after every
 * [RecordStore] write and it collapses a burst of edits into one export after
 * [DEBOUNCE_MILLIS] of quiet. [exportNow] runs immediately and is what `onStop`/app-background
 * should call directly (ticket 12 answer point 2: "debounced after writes plus on app background").
 *
 * **Import runs on app foreground and after export** (ticket 13 answer: "offline is out of scope...
 * so import runs on app foreground and after export") - [importAll] is the entry point for both;
 * [exportNow] itself calls it afterward so a device that just pushed its own edits immediately picks
 * up whatever a SECOND device wrote to the same file since this device's last look.
 *
 * **The row-level merge is BINDING** (ticket 13's resolution, restated verbatim in ticket 20 item
 * 4): keyed by record id plus updatedAt, latest wins, never whole-file replace. See
 * [mergeRecordSheet]'s own doc comment for the concrete tie-breaking rule this class uses, which is
 * a deliberate, documented interpretation of "latest wins" against a real constraint the ticket
 * does not resolve for me - a hand edit made directly in Sheets never bumps the file's own
 * `updatedAt` cell (nothing in the spreadsheet does that automatically), so "the row's stated
 * timestamp" and "whether the row's content actually changed" are two different signals this class
 * has to reconcile, not one.
 *
 * **Scope cut, stated plainly rather than silently dropped**: the `_definitions` sheet is exported
 * in full every time (ticket 13: "aspect definitions sync through a definitions sheet... under the
 * same merge rule") and PARSED in full by [MirrorCodec], but this class does not yet act on a
 * `_definitions` row that names a brand-new aspect/record-type/field (a blank id column). Creating
 * schema purely from a hand-typed spreadsheet row needs the same guardrails the not-yet-built aspect
 * editor UI will need (position ordering, `primaryAmountFieldId`/`primaryDueDateFieldId` choice,
 * `ownerPluginId`/`locked` safety) and is out of this ticket's bounded scope - [importAll]'s result
 * surfaces every such row as a named, worded warning rather than silently ignoring it, so nothing is
 * lost quietly; wiring it up is a follow-up ticket. An EXISTING field's name/required/position can
 * still be hand-edited and re-imported today (see [mergeDefinitions]).
 */
class MirrorSync(private val context: Context) {

    private val db = CarDatabase.getDatabase(context)
    private val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null
    private val exportMutex = Mutex() // one export/import pass at a time - never two racing writers

    private val _lastResult = MutableStateFlow<MirrorRunResult?>(null)
    /** The most recent export+import pass's outcome, for the settings stub (ticket 20 item 5). */
    val lastResult: StateFlow<MirrorRunResult?> = _lastResult.asStateFlow()

    data class MirrorRunResult(
        val at: Long,
        val exported: List<String>,
        val exportFailures: Map<String, String>,
        val importSummaries: Map<String, ImportSummary>,
    )

    data class ImportSummary(
        val created: Int,
        val updated: Int,
        val unchanged: Int,
        val quarantined: List<String>,
        val trashed: Int,
        val definitionWarnings: List<String>,
    )

    /** Debounced export - call after every [RecordStore] write. Collapses a burst into one export
     * [DEBOUNCE_MILLIS] after the last call, matching ticket 12 answer point 2's promise. */
    fun scheduleExport() {
        debounceJob?.cancel()
        debounceJob = syncScope.launch {
            delay(DEBOUNCE_MILLIS)
            runCatching { exportAllAndImport() }
                .onFailure { Log.w(TAG, "debounced export failed: ${it.message}", it) }
        }
    }

    /** Immediate export, no debounce - the app-background trigger (ticket 12 answer point 2) and
     * the settings stub's manual "sync now" both call this directly. */
    suspend fun exportNow(): MirrorRunResult {
        debounceJob?.cancel()
        return exportAllAndImport()
    }

    /** Import only, no export first - the app-FOREGROUND trigger (ticket 13's resolution: "import
     * runs on app foreground and after export"). Exposed separately from [exportNow] because a
     * foreground resume should pick up a second device's edits without also re-pushing this
     * device's own unchanged state first. */
    suspend fun importOnly(): Map<String, ImportSummary> = exportMutex.withLock { importAll() }

    private suspend fun exportAllAndImport(): MirrorRunResult = exportMutex.withLock {
        val treeUri = MirrorFolderPreferences.treeUri.value
        if (treeUri == null) {
            val result = MirrorRunResult(System.currentTimeMillis(), emptyList(), emptyMap(), emptyMap())
            _lastResult.value = result
            return@withLock result
        }

        val aspects = db.aspectDao().listActive()
        val exported = mutableListOf<String>()
        val exportFailures = mutableMapOf<String, String>()

        for (aspect in aspects) {
            val slug = slugFor(aspect)
            val export = buildExport(aspect)
            val bytes = MirrorCodec.recordsToWorkbookBytes(export)
            when (val result = MirrorStore.write(context, treeUri, "$slug.xlsx", bytes)) {
                MirrorStore.WriteResult.Success -> {
                    MirrorStateStore.recordExport(context, slug, System.currentTimeMillis(), MirrorStore.sha256(bytes))
                    exported += slug
                }
                is MirrorStore.WriteResult.Failure -> {
                    MirrorStateStore.quarantine(context, slug, result.reason)
                    exportFailures[slug] = result.reason
                    Log.w(TAG, "export quarantined for $slug: ${result.reason}")
                }
            }
        }

        val importSummaries = importAll()
        val runResult = MirrorRunResult(System.currentTimeMillis(), exported, exportFailures, importSummaries)
        _lastResult.value = runResult
        runResult
    }

    private suspend fun buildExport(aspect: Aspect): MirrorCodec.MirrorAspectExport {
        val recordTypes = db.recordTypeDao().listByAspect(aspect.id).map { rt ->
            val fieldDefs = db.fieldDefDao().forRecordType(rt.id)
            val records = db.engineRecordDao().activeByRecordType(rt.id)
            MirrorCodec.MirrorRecordTypeExport(rt, fieldDefs, records)
        }
        return MirrorCodec.MirrorAspectExport(aspect, recordTypes)
    }

    private fun slugFor(aspect: Aspect): String =
        aspect.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "aspect-${aspect.id}" }

    // ---- import: the gate, then the binding row-level merge ---------------------------------

    private suspend fun importAll(): Map<String, ImportSummary> {
        val treeUri = MirrorFolderPreferences.treeUri.value ?: return emptyMap()
        val aspects = db.aspectDao().listActive()
        val summaries = mutableMapOf<String, ImportSummary>()

        for (aspect in aspects) {
            val slug = slugFor(aspect)
            when (val read = MirrorStore.read(context, treeUri, "$slug.xlsx")) {
                MirrorStore.ReadResult.NotFound -> Unit // never exported yet - nothing to import
                is MirrorStore.ReadResult.Failure -> {
                    MirrorStateStore.quarantine(context, slug, read.reason)
                    Log.w(TAG, "import read failed for $slug: ${read.reason}")
                }
                is MirrorStore.ReadResult.Found -> {
                    val state = MirrorStateStore.get(context, slug)
                    // Change detection (ticket 20 item 4): identical to both the last thing we
                    // exported AND the last thing we imported means nothing to do - neither this
                    // app nor a human touched the file since the last look.
                    if (read.sha256 == state.lastImportHash && read.sha256 == state.lastExportHash) continue

                    summaries[slug] = importAspectFile(aspect, read.bytes, state)
                    MirrorStateStore.recordImport(context, slug, System.currentTimeMillis(), read.sha256)
                }
            }
        }
        return summaries
    }

    private suspend fun importAspectFile(
        aspect: Aspect,
        bytes: ByteArray,
        state: MirrorStateStore.AspectSyncState,
    ): ImportSummary {
        val parsed = runCatching { MirrorCodec.workbookBytesToParsedWorkbook(bytes) }
            .getOrElse { e ->
                MirrorStateStore.quarantine(context, slugFor(aspect), "couldn't parse the workbook: ${e.message}")
                return ImportSummary(0, 0, 0, listOf("workbook parse failed: ${e.message}"), 0, emptyList())
            }

        var created = 0
        var updated = 0
        var unchanged = 0
        var trashed = 0
        val quarantined = mutableListOf<String>()

        val recordTypes = db.recordTypeDao().listByAspect(aspect.id)
        for (rt in recordTypes) {
            val sheet = parsed.recordSheets.firstOrNull { it.recordTypeId == rt.id } ?: continue
            val fieldDefs = db.fieldDefDao().forRecordType(rt.id)
            val outcome = mergeRecordSheet(rt.id, fieldDefs, sheet, state)
            created += outcome.created
            updated += outcome.updated
            unchanged += outcome.unchanged
            trashed += outcome.trashed
            quarantined += outcome.quarantined
        }

        val definitionWarnings = mergeDefinitions(parsed.definitions)

        return ImportSummary(created, updated, unchanged, quarantined, trashed, definitionWarnings)
    }

    internal data class SheetMergeOutcome(
        val created: Int,
        val updated: Int,
        val unchanged: Int,
        val trashed: Int,
        val quarantined: List<String>,
    )

    /**
     * The binding row-level merge for one record type's sheet (ticket 13/20: keyed by record id
     * plus updatedAt, latest wins, never whole-file replace).
     *
     * **The concrete tie-break, since a plain Sheets cell edit never bumps the file's own
     * `updatedAt` column** (this class's own doc comment): for a row whose id matches a live local
     * record,
     * 1. If the row's field values are IDENTICAL to what is stored locally right now, it is a
     *    no-op regardless of what the timestamp columns say - there is nothing to merge.
     * 2. Otherwise the row's content differs from local. If the row's stated `updatedAt` is
     *    strictly newer than the local record's, the file wins outright (the unambiguous case the
     *    ticket names).
     * 3. Otherwise - content differs but the stated timestamp is not newer - this is exactly what a
     *    hand edit in Sheets looks like (the human changed a cell; nothing changed the timestamp
     *    cell alongside it). It is treated as a genuine edit and applied, UNLESS the local record's
     *    own `updatedAt` is newer than [state]'s last export stamp, meaning the LOCAL copy changed
     *    since this exact file was last generated - in that case local is provably the more recent
     *    edit and wins, and the file's stale content is left unapplied (it will be corrected on the
     *    next export).
     *
     * **Reconciled rows are rejected as read-only** (ticket 12 answer point 4, ticket 20 item 4) -
     * checked BEFORE any of the above: if the local record's own provenance is `DETERMINISTIC` or
     * `LLM_RECONCILED`, any content difference at all is quarantined with a worded reason instead of
     * applied, full stop; un-reconciling only happens through the app's own UI.
     *
     * **Remote delete**: a local record of this type NOT present among [sheet]'s rows, whose
     * `updatedAt` is at or before [state]'s last export stamp, existed at the time of that export
     * and is now missing from the file - the human deleted its row in Sheets - and is trashed via
     * [RecordStore.delete]. A local record newer than the export stamp was simply never in any
     * exported copy yet and is left alone.
     */
    internal suspend fun mergeRecordSheet(
        recordTypeId: Long,
        fieldDefs: List<FieldDef>,
        sheet: MirrorCodec.ParsedRecordSheet,
        state: MirrorStateStore.AspectSyncState,
    ): SheetMergeOutcome {
        var created = 0
        var updated = 0
        var unchanged = 0
        var trashed = 0
        val quarantined = mutableListOf<String>()
        val seenIds = mutableSetOf<Long>()

        for (row in sheet.rows) {
            if (row.fieldParseErrors.isNotEmpty()) {
                quarantined += "row ${row.rowNumber}: " + row.fieldParseErrors.values.joinToString("; ")
                continue
            }
            val choiceError = checkChoiceLegality(fieldDefs, row.fieldValues)
            if (choiceError != null) {
                quarantined += "row ${row.rowNumber}: $choiceError"
                continue
            }

            if (row.recordId == null) {
                // Blank id = a hand-added row. A fully blank data row was already dropped by the
                // codec (physicalCellCount == 0); a row with SOME data but no id is a real create.
                if (row.fieldValues.values.all { it == null }) continue
                val formErrors = GeneratedFormValidation.validate(fieldDefs, row.fieldValues)
                if (formErrors.isNotEmpty()) {
                    quarantined += "row ${row.rowNumber}: " + formErrors.joinToString("; ") { it.message }
                    continue
                }
                when (val write = recordStore.create(recordTypeId, row.fieldValues, RecordProvenance.USER)) {
                    is RecordStore.WriteResult.Success -> created++
                    is RecordStore.WriteResult.Failure -> quarantined += "row ${row.rowNumber}: ${write.reason}"
                }
                continue
            }

            seenIds += row.recordId
            val existing = db.engineRecordDao().getById(row.recordId)
            if (existing == null || existing.deletedAt != null) {
                quarantined += "row ${row.rowNumber}: record #${row.recordId} " +
                    (if (existing == null) "no longer exists locally" else "is in trash locally - restore it in the app first")
                continue
            }

            val readOnly = existing.provenance == RecordProvenance.DETERMINISTIC ||
                existing.provenance == RecordProvenance.LLM_RECONCILED
            val existingPayload = org.json.JSONObject(existing.payload)
            val contentChanged = fieldDefs.any { fd ->
                if (fd.type == FieldType.COMPUTED) return@any false
                if (!row.fieldValues.containsKey(fd.id)) return@any false
                valueDiffers(fd.type, row.fieldValues[fd.id], existingPayload, fd.id)
            }

            if (!contentChanged) { unchanged++; continue }

            if (readOnly) {
                quarantined += "row ${row.rowNumber}: record #${row.recordId} is ${existing.provenance} - " +
                    "reconciled rows are read-only in the mirror, edit it in the app"
                continue
            }

            val fileNewer = row.updatedAt != null && row.updatedAt > existing.updatedAt
            val localMovedSinceExport = state.lastExportAt != null && existing.updatedAt > state.lastExportAt
            val applyEdit = fileNewer || !localMovedSinceExport
            if (!applyEdit) { unchanged++; continue } // local is the more recent edit - file's content is stale

            val formErrors = GeneratedFormValidation.validate(fieldDefs, row.fieldValues)
            if (formErrors.isNotEmpty()) {
                quarantined += "row ${row.rowNumber}: " + formErrors.joinToString("; ") { it.message }
                continue
            }
            val now = maxOf(row.updatedAt ?: 0L, existing.updatedAt + 1, System.currentTimeMillis())
            when (val write = recordStore.update(row.recordId, row.fieldValues, now)) {
                is RecordStore.WriteResult.Success -> updated++
                is RecordStore.WriteResult.Failure -> quarantined += "row ${row.rowNumber}: ${write.reason}"
            }
        }

        // Remote delete: local active records of this type absent from the file, last touched at
        // or before the file's own export stamp.
        if (state.lastExportAt != null) {
            val localActive = db.engineRecordDao().activeByRecordType(recordTypeId)
            for (local in localActive) {
                if (local.id in seenIds) continue
                if (local.updatedAt <= state.lastExportAt) {
                    recordStore.delete(local.id)
                    trashed++
                }
            }
        }

        return SheetMergeOutcome(created, updated, unchanged, trashed, quarantined)
    }

    internal fun valueDiffers(type: FieldType, fileValue: Any?, existingPayload: org.json.JSONObject, fieldDefId: Long): Boolean {
        val key = fieldDefId.toString()
        val existingRaw = if (existingPayload.has(key) && !existingPayload.isNull(key)) existingPayload.opt(key) else null
        return when (type) {
            FieldType.MONEY_CENTS, FieldType.REFERENCE, FieldType.DATE, FieldType.DATETIME ->
                (fileValue as? Long) != (existingRaw as? Number)?.toLong()
            FieldType.NUMBER, FieldType.RATING ->
                (fileValue as? Double) != (existingRaw as? Number)?.toDouble()
            FieldType.BOOLEAN -> (fileValue as? Boolean) != (existingRaw as? Boolean)
            FieldType.MULTI_SELECT_CHOICE -> {
                val existingList = (existingRaw as? org.json.JSONArray)
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
                (fileValue as? List<*>) != existingList
            }
            else -> (fileValue as? String) != (existingRaw as? String)
        }
    }

    internal fun checkChoiceLegality(fieldDefs: List<FieldDef>, values: Map<Long, Any?>): String? {
        for (fd in fieldDefs) {
            val v = values[fd.id] ?: continue
            when (fd.type) {
                FieldType.CHOICE -> {
                    val options = FieldConfig.choiceOptions(fd.config)
                    if (options.isNotEmpty() && v !is String) return "'${fd.name}' has an unexpected value"
                    if (options.isNotEmpty() && v !in options) return "'${fd.name}' = '$v' is not one of: ${options.joinToString(", ")}"
                }
                FieldType.MULTI_SELECT_CHOICE -> {
                    val options = FieldConfig.choiceOptions(fd.config)
                    val chosen = (v as? List<*>).orEmpty()
                    val illegal = chosen.filterNot { it in options }
                    if (options.isNotEmpty() && illegal.isNotEmpty()) {
                        return "'${fd.name}' has values not in the option list: ${illegal.joinToString(", ")}"
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    /**
     * Bounded definitions merge (see this class's own doc comment for the scope cut): applies a
     * hand edit to an EXISTING, unlocked field's `name`/`required`/`position` when the file's row
     * differs from what is stored - the same "content differs, apply it" posture [mergeRecordSheet]
     * uses, without a timestamp tie-break since [FieldDef] has no analogous "did a human touch this"
     * signal beyond its own content. A `locked` field (plugin-owned, per [FieldDef]'s own doc
     * comment) is never touched even if its row differs - matching [FieldDef.locked]'s existing
     * contract that only a plugin sets it. A row naming an aspect/record-type/field id that does not
     * exist locally is reported as a warning, never silently dropped and never acted on.
     */
    internal suspend fun mergeDefinitions(rows: List<MirrorCodec.ParsedDefinitionRow>): List<String> {
        val warnings = mutableListOf<String>()
        for (row in rows) {
            if (row.fieldDefId == null) {
                warnings += "'${row.fieldName}' on '${row.recordTypeName}' has no field id - creating a new " +
                    "field/record-type/aspect purely from the spreadsheet isn't wired up yet (see MirrorSync's doc comment)"
                continue
            }
            val existing = db.fieldDefDao().getById(row.fieldDefId)
            if (existing == null) {
                warnings += "field id ${row.fieldDefId} ('${row.fieldName}') no longer exists locally"
                continue
            }
            if (existing.locked) continue // plugin-owned - never hand-edited through the mirror
            val nameChanged = existing.name != row.fieldName
            val requiredChanged = existing.required != row.required
            val positionChanged = existing.position != row.position
            if (nameChanged || requiredChanged || positionChanged) {
                db.fieldDefDao().update(
                    existing.copy(
                        name = row.fieldName,
                        required = row.required,
                        position = row.position,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        return warnings
    }

    companion object {
        private const val TAG = "MirrorSync"
        private const val DEBOUNCE_MILLIS = 5_000L
    }
}
