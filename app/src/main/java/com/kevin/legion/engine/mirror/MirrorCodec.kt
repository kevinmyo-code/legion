package com.kevin.legion.engine.mirror

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.engine.PayloadCodec
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.ReadableWorkbook
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import kotlin.streams.asSequence

/**
 * The xlsx mirror's codec (ticket 20 item 2, ticket 12's answer) - **pure functions only**.
 * [recordsToWorkbookBytes] and [workbookBytesToParsedWorkbook] touch no SAF, no Room, no
 * [android.content.Context]; every input is a plain Kotlin value already read out of the engine's
 * tables by the caller ([MirrorSync]). That is deliberate (ticket 20 item 2's "unit-testable
 * without SAF") and it is what [MirrorCodecTest] exercises: records to bytes to rows, bit-exact on
 * ids/cents/dates, with no fake filesystem or Robolectric involved.
 *
 * **No Robolectric needed at all** - fastexcel (both `fastexcel` the writer and `fastexcel-reader`)
 * is plain-JVM, ships no Android asset, and touches no `android.*` API
 * (`.scratch/aspect-engine/research/02-xlsx-on-android.md` section "Test implications"), unlike
 * PdfBox-Android which needs Robolectric to shadow `AssetManager` for its bundled fonts. Confirmed
 * by [MirrorCodecTest] actually running on the bare JVM unit-test runner.
 *
 * **One workbook per aspect, one sheet per record type, plus one `_definitions` sheet**
 * (ticket 12 answer point 1). The `_definitions` sheet is written from the SAME [FieldDef]/
 * [RecordType]/[Aspect] rows the record sheets' headers are derived from, and
 * [workbookBytesToParsedWorkbook] deliberately parses it FIRST and reconstructs each record
 * sheet's field-name-to-[FieldDef] mapping from it, rather than requiring the caller to hand back
 * an external schema to interpret the record sheets against. That makes parsing self-describing:
 * a workbook exported by this exact function, and only edited by hand afterward (never given a
 * schema import from elsewhere), parses back correctly with nothing beyond its own bytes.
 *
 * **Embedded xlsx `dataValidation` rules are decoration, never enforcement**
 * (ticket 12 answer point 3, ticket 02's research: "nothing establishes enforcement on either
 * mobile editor"). [MirrorSync]'s import gate is what actually enforces every rule this codec only
 * suggests to whichever editor opens the file.
 *
 * **Fixed header contract** (ticket 20 item 2): row 1 of every record sheet is
 * `id (protected) | createdAt | updatedAt | provenance (protected) | <one column per field>`.
 * Money is a **plain integer-cents number cell**, header suffixed `(cents)` - never a currency
 * format, which invites a decimal edit (research 02 section 5). Dates/datetimes are plain
 * integer-epoch-millis number cells, header suffixed `(epoch ms)`, for the identical reason: an
 * Excel date SERIAL is a display convention this codec deliberately never depends on.
 * [FieldType.COMPUTED] columns are suffixed `(computed, protected)` and are NEVER read back into a
 * write - see [ParsedRecordRow.fieldValues]'s doc.
 *
 * **fastexcel 0.19.0 exposes only whole-sheet [org.dhatim.fastexcel.Worksheet.protect], no
 * per-cell lock API** - traced by reading `StyleSetter.java`/`Range.java` in the fastexcel source
 * before writing this class; there is no `locked()`/`hidden()` setter to unlock everything except
 * the id/provenance/computed columns. Real cell-level protection is therefore NOT implemented -
 * the header-name suffixes above and the provenance cell's own `(read-only)` suffix are the honest
 * substitute, worded so a hand editor can see it without Excel enforcing anything. This is stated
 * plainly rather than silently downgraded, per ticket 12 answer point 3's framing.
 */
object MirrorCodec {

    private const val DEFINITIONS_SHEET = "_definitions"
    private const val COL_ID = 0
    private const val COL_CREATED_AT = 1
    private const val COL_UPDATED_AT = 2
    private const val COL_PROVENANCE = 3
    private const val FIRST_FIELD_COL = 4

    // ---- export input shapes ---------------------------------------------------------------

    /** One record type's full export shape: its live schema plus its LIVE (non-trashed) records,
     * already fetched by the caller. [fieldDefs] must be in the order the caller wants columns to
     * appear - [MirrorSync] passes them in [FieldDef.position] order, matching every other engine
     * surface's field ordering convention. */
    data class MirrorRecordTypeExport(
        val recordType: RecordType,
        val fieldDefs: List<FieldDef>,
        val records: List<EngineRecord>,
    )

    /** One aspect's full export shape - what [recordsToWorkbookBytes] turns into one workbook. */
    data class MirrorAspectExport(
        val aspect: Aspect,
        val recordTypes: List<MirrorRecordTypeExport>,
    )

    // ---- parse output shapes ------------------------------------------------------------------

    /** One row of a record sheet, already type-coerced against the schema recovered from the same
     * workbook's `_definitions` sheet - see this object's own doc comment for why that recovery
     * happens internally rather than needing an external schema argument.
     *
     * [recordId] is null for a row with no `id` cell - a brand-new, hand-added row.
     * [fieldValues] maps [FieldDef.id] to a value already shaped for
     * [com.kevin.legion.engine.RecordStore.create]/`update`'s `fieldValues` parameter -
     * [FieldType.COMPUTED] fields are NEVER included here (matching [RecordStore]'s own "computed
     * entries in fieldValues are IGNORED" rule - there is nothing to ignore because this codec never
     * puts one there in the first place). [fieldParseErrors] carries a worded reason for any cell
     * this codec could not coerce to its field's expected shape (e.g. text in a MONEY_CENTS cell) -
     * [MirrorSync]'s gate turns a non-empty map here into a quarantine, in words, per row.
     * [unmappedHeaders] names any column header in this row's sheet that matched no field in the
     * recovered schema (a renamed or stale header) - reported as a warning, never a row failure, since
     * an unmapped column carries no data this codec can lose (it was never interpreted as a field).
     */
    data class ParsedRecordRow(
        val rowNumber: Int,
        val recordId: Long?,
        val createdAt: Long?,
        val updatedAt: Long?,
        val provenance: RecordProvenance?,
        val provenanceReadOnly: Boolean,
        val fieldValues: Map<Long, Any?>,
        val fieldParseErrors: Map<Long, String>,
        val unmappedHeaders: List<String>,
    )

    data class ParsedRecordSheet(
        val recordTypeId: Long,
        val recordTypeName: String,
        val rows: List<ParsedRecordRow>,
    )

    /** One row of the `_definitions` sheet - see [FieldDef]/[RecordType]/[Aspect] for what each
     * column mirrors. Nullable ids mean "not yet created" - [MirrorSync] treats those as new-schema
     * proposals it currently only WARNS about (ticket 20's own scope note: creating a brand-new
     * aspect/record-type/field purely from a hand-typed spreadsheet row is deferred - see
     * [MirrorSync]'s doc comment). */
    data class ParsedDefinitionRow(
        val aspectId: Long?,
        val aspectName: String,
        val recordTypeId: Long?,
        val recordTypeName: String,
        val fieldDefId: Long?,
        val fieldName: String,
        val fieldType: FieldType?,
        val required: Boolean,
        val position: Int,
        val config: String?,
        val locked: Boolean,
        val updatedAt: Long?,
    )

    data class ParsedWorkbook(
        val recordSheets: List<ParsedRecordSheet>,
        val definitions: List<ParsedDefinitionRow>,
    )

    // ---- write ----------------------------------------------------------------------------------

    /** Every field-type header suffix, in one place so [headerFor] (write side) and
     * [stripHeaderSuffix] (read side) can never disagree about the contract. */
    private const val SUFFIX_CENTS = " (cents)"
    private const val SUFFIX_EPOCH = " (epoch ms)"
    private const val SUFFIX_COMPUTED = " (computed, protected)"

    private fun headerFor(fd: FieldDef): String = when (fd.type) {
        FieldType.MONEY_CENTS -> fd.name + SUFFIX_CENTS
        FieldType.DATE, FieldType.DATETIME -> fd.name + SUFFIX_EPOCH
        FieldType.COMPUTED -> fd.name + SUFFIX_COMPUTED
        else -> fd.name
    }

    private fun stripHeaderSuffix(header: String): String = header
        .removeSuffix(SUFFIX_CENTS)
        .removeSuffix(SUFFIX_EPOCH)
        .removeSuffix(SUFFIX_COMPUTED)

    /** Builds one aspect's workbook bytes - the WRITE half of the codec. Pure: no I/O beyond the
     * in-memory [ByteArrayOutputStream] fastexcel itself writes into. */
    fun recordsToWorkbookBytes(export: MirrorAspectExport): ByteArray {
        val out = ByteArrayOutputStream()
        val workbook = Workbook(out, "LEGION", null)

        val usedSheetNames = mutableSetOf<String>()
        for (rte in export.recordTypes) {
            val sheetName = uniqueSheetName(rte.recordType.name, usedSheetNames)
            val ws = workbook.newWorksheet(sheetName)
            writeRecordSheet(ws, rte)
        }
        writeDefinitionsSheet(workbook.newWorksheet(DEFINITIONS_SHEET), export)
        workbook.finish()
        return out.toByteArray()
    }

    /** Excel sheet names: max 31 chars, no `\/?*[]:`, unique within the workbook. Personal-app
     * record-type names are short and few, so a bare truncate+dedupe is sufficient - no need for a
     * hashing scheme a bulk-generated workbook might require. */
    private fun uniqueSheetName(raw: String, used: MutableSet<String>): String {
        val sanitized = raw.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().take(31).ifBlank { "Sheet" }
        var candidate = sanitized
        var n = 2
        while (!used.add(candidate)) {
            val suffix = " $n"
            candidate = sanitized.take(31 - suffix.length) + suffix
            n++
        }
        return candidate
    }

    private fun writeRecordSheet(ws: org.dhatim.fastexcel.Worksheet, rte: MirrorRecordTypeExport) {
        ws.value(0, COL_ID, "id (protected)")
        ws.value(0, COL_CREATED_AT, "createdAt")
        ws.value(0, COL_UPDATED_AT, "updatedAt")
        ws.value(0, COL_PROVENANCE, "provenance (protected)")
        rte.fieldDefs.forEachIndexed { i, fd -> ws.value(0, FIRST_FIELD_COL + i, headerFor(fd)) }

        rte.records.forEachIndexed { rowIndex, record ->
            val r = rowIndex + 1
            ws.value(r, COL_ID, record.id.toDouble())
            ws.value(r, COL_CREATED_AT, record.createdAt.toDouble())
            ws.value(r, COL_UPDATED_AT, record.updatedAt.toDouble())
            val readOnly = record.provenance == RecordProvenance.DETERMINISTIC ||
                record.provenance == RecordProvenance.LLM_RECONCILED
            ws.value(
                r, COL_PROVENANCE,
                record.provenance.name + if (readOnly) " (read-only)" else "",
            )
            val payload = JSONObject(record.payload)
            rte.fieldDefs.forEachIndexed { i, fd -> writeFieldCell(ws, r, FIRST_FIELD_COL + i, fd, payload) }
        }

        // Decoration only (this class's own doc comment) - generated from the field defs so the
        // honest editor gets a dropdown/range hint even though nothing enforces it.
        val lastRow = rte.records.size // row index of the last data row (row 0 is the header)
        if (lastRow > 0) {
            rte.fieldDefs.forEachIndexed { i, fd -> addValidation(ws, fd, FIRST_FIELD_COL + i, lastRow) }
        }
    }

    private fun writeFieldCell(
        ws: org.dhatim.fastexcel.Worksheet,
        r: Int,
        c: Int,
        fd: FieldDef,
        payload: JSONObject,
    ) {
        when (fd.type) {
            FieldType.MONEY_CENTS, FieldType.REFERENCE, FieldType.DATE, FieldType.DATETIME -> {
                val v = PayloadCodec.readLong(payload, fd.id)
                if (v != null) ws.value(r, c, v.toDouble())
            }
            FieldType.NUMBER, FieldType.RATING -> {
                val v = PayloadCodec.readDouble(payload, fd.id)
                if (v != null) ws.value(r, c, v)
            }
            FieldType.BOOLEAN -> {
                val k = PayloadCodec.key(fd.id)
                if (payload.has(k) && !payload.isNull(k)) ws.value(r, c, payload.optBoolean(k))
            }
            FieldType.MULTI_SELECT_CHOICE -> {
                val k = PayloadCodec.key(fd.id)
                val arr = payload.optJSONArray(k)
                if (arr != null) {
                    val joined = (0 until arr.length()).joinToString(", ") { arr.optString(it) }
                    ws.value(r, c, joined)
                }
            }
            FieldType.COMPUTED -> {
                // Read-only display value - never round-tripped into a write. See this object's
                // doc comment: writeComputed's own JSON shape is just stringified for a human to see.
                val k = PayloadCodec.key(fd.id)
                if (payload.has(k) && !payload.isNull(k)) ws.value(r, c, payload.opt(k)?.toString().orEmpty())
            }
            else -> {
                val v = PayloadCodec.readString(payload, fd.id)
                if (v != null) ws.value(r, c, v)
            }
        }
    }

    private fun addValidation(ws: org.dhatim.fastexcel.Worksheet, fd: FieldDef, col: Int, lastRow: Int) {
        if (fd.type == FieldType.COMPUTED) return // read-only column, nothing to validate
        val range = ws.range(1, col, lastRow, col)
        when (fd.type) {
            FieldType.CHOICE -> {
                val options = FieldConfig.choiceOptions(fd.config)
                if (options.isNotEmpty()) range.validateWithListByFormula("\"" + options.joinToString(",") + "\"")
            }
            FieldType.BOOLEAN -> range.validateWithListByFormula("\"TRUE,FALSE\"")
            FieldType.MONEY_CENTS, FieldType.NUMBER, FieldType.RATING, FieldType.DATE,
            FieldType.DATETIME, FieldType.REFERENCE -> {
                // fastexcel-writer's own CellAddress is package-private (traced against its
                // source), so the top-left cell reference for a relative ISNUMBER() formula is
                // built by hand rather than borrowed from the library's internals.
                range.validateWithFormula("ISNUMBER(${columnLetters(col)}2)")
            }
            else -> Unit
        }
    }

    /** Zero-based column index to Excel column letters ("A", "Z", "AA"...) - fastexcel-writer keeps
     * its own equivalent package-private, so this is a small, independent reimplementation rather
     * than a reflection hack against a library internal. */
    private fun columnLetters(col: Int): String {
        var n = col
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + (n % 26)))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    private fun writeDefinitionsSheet(ws: org.dhatim.fastexcel.Worksheet, export: MirrorAspectExport) {
        val headers = listOf(
            "aspectId (protected)", "aspectName", "recordTypeId (protected)", "recordTypeName",
            "fieldDefId (protected)", "fieldName", "fieldType", "required", "position", "config",
            "locked (protected)", "updatedAt",
        )
        headers.forEachIndexed { i, h -> ws.value(0, i, h) }

        var r = 1
        for (rte in export.recordTypes) {
            for (fd in rte.fieldDefs) {
                ws.value(r, 0, export.aspect.id.toDouble())
                ws.value(r, 1, export.aspect.name)
                ws.value(r, 2, rte.recordType.id.toDouble())
                ws.value(r, 3, rte.recordType.name)
                ws.value(r, 4, fd.id.toDouble())
                ws.value(r, 5, fd.name)
                ws.value(r, 6, fd.type.name)
                ws.value(r, 7, fd.required)
                ws.value(r, 8, fd.position.toDouble())
                if (fd.config != null) ws.value(r, 9, fd.config)
                ws.value(r, 10, fd.locked)
                ws.value(r, 11, fd.updatedAt.toDouble())
                r++
            }
        }
    }

    // ---- read -----------------------------------------------------------------------------------

    /** Parses a mirror workbook's bytes back into rows - the READ half of the codec, and, per this
     * object's own doc comment, entirely self-describing from the `_definitions` sheet it reads
     * first. Never throws on a malformed workbook it can characterize - a workbook that is not a
     * valid zip/xlsx at all is the ONE case left to throw (there is no row-level reason to attach
     * that failure to), and [MirrorStore]'s caller is the one place equipped to turn that into a
     * quarantine of the whole mirror file rather than a row.
     */
    fun workbookBytesToParsedWorkbook(bytes: ByteArray): ParsedWorkbook {
        ReadableWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            val definitions = wb.findSheet(DEFINITIONS_SHEET).orElse(null)
                ?.let { parseDefinitionsSheet(it) }
                ?: emptyList()

            // Recover each record type's expected columns from the definitions rows, in the exact
            // column order [writeRecordSheet] used - position order, ties broken by fieldDefId so
            // the order is deterministic even if two fields somehow share a position.
            val byRecordType = definitions
                .filter { it.recordTypeId != null }
                .groupBy { it.recordTypeId!! }
                .mapValues { (_, defs) -> defs.sortedWith(compareBy({ it.position }, { it.fieldDefId ?: Long.MAX_VALUE })) }

            // Stream -> List via kotlin.streams.asSequence (stdlib, JVM-only, no Java-16 Stream.toList()
            // dependency - keeps this working under whatever desugaring level the app targets).
            val allSheets = wb.sheets.asSequence().toList()
            val recordSheets = mutableListOf<ParsedRecordSheet>()
            for (sheet in allSheets) {
                if (sheet.name == DEFINITIONS_SHEET) continue
                val recordTypeDefs = byRecordType.values.firstOrNull { defs ->
                    defs.isNotEmpty() && sheetLooksLike(sheet.name, defs.first().recordTypeName)
                } ?: continue
                val recordTypeId = recordTypeDefs.first().recordTypeId ?: continue
                recordSheets += parseRecordSheet(sheet, recordTypeId, recordTypeDefs.first().recordTypeName, recordTypeDefs)
            }
            return ParsedWorkbook(recordSheets, definitions)
        }
    }

    /** Sheet names are sanitized/truncated/de-duplicated on write ([uniqueSheetName]) so an exact
     * string match against the record type's raw name is not always safe (a truncated or " 2"-suffixed
     * name). A prefix match against the sanitized form is close enough for a personal-app record-type
     * count, and a wrong pairing here would show up immediately as every column becoming "unmapped"
     * on that sheet - loud, not silent. */
    private fun sheetLooksLike(sheetName: String, recordTypeName: String): Boolean {
        val sanitized = recordTypeName.replace(Regex("[\\\\/?*\\[\\]:]"), " ").trim().take(31)
        return sheetName == sanitized || sheetName.startsWith(sanitized)
    }

    private fun parseDefinitionsSheet(sheet: org.dhatim.fastexcel.reader.Sheet): List<ParsedDefinitionRow> {
        val rows = sheet.read()
        if (rows.isEmpty()) return emptyList()
        val out = mutableListOf<ParsedDefinitionRow>()
        for (row in rows.drop(1)) {
            val fieldName = row.getCellAsString(5).orElse(null) ?: continue // no field name = blank row, skip
            out += ParsedDefinitionRow(
                aspectId = row.getCellAsNumber(0).orElse(null)?.toLong(),
                aspectName = row.getCellAsString(1).orElse(""),
                recordTypeId = row.getCellAsNumber(2).orElse(null)?.toLong(),
                recordTypeName = row.getCellAsString(3).orElse(""),
                fieldDefId = row.getCellAsNumber(4).orElse(null)?.toLong(),
                fieldName = fieldName,
                fieldType = row.getCellAsString(6).orElse(null)?.let { runCatching { FieldType.valueOf(it) }.getOrNull() },
                required = row.getCellAsBoolean(7).orElse(false),
                position = row.getCellAsNumber(8).orElse(BigDecimal.ZERO).toInt(),
                config = row.getCellAsString(9).orElse(null),
                locked = row.getCellAsBoolean(10).orElse(false),
                updatedAt = row.getCellAsNumber(11).orElse(null)?.toLong(),
            )
        }
        return out
    }

    private fun parseRecordSheet(
        sheet: org.dhatim.fastexcel.reader.Sheet,
        recordTypeId: Long,
        recordTypeName: String,
        defs: List<ParsedDefinitionRow>,
    ): ParsedRecordSheet {
        val rows = sheet.read()
        if (rows.isEmpty()) return ParsedRecordSheet(recordTypeId, recordTypeName, emptyList())

        val header = rows[0]
        // Map every data column (index >= FIRST_FIELD_COL) to the definition it matches by header
        // NAME (suffix-stripped) - see this object's doc comment for why name matching against the
        // SAME workbook's own definitions sheet is safe rather than fragile.
        val colToDef = mutableMapOf<Int, ParsedDefinitionRow>()
        val unmapped = mutableListOf<String>()
        for (c in FIRST_FIELD_COL until header.cellCount) {
            val headerText = header.getCellAsString(c).orElse(null) ?: continue
            val baseName = stripHeaderSuffix(headerText)
            val match = defs.firstOrNull { it.fieldName == baseName }
            if (match != null) colToDef[c] = match else unmapped += headerText
        }

        val parsedRows = mutableListOf<ParsedRecordRow>()
        for (row in rows.drop(1)) {
            if (row.physicalCellCount == 0) continue // fully blank row - never a row, per fastexcel's own semantics
            val recordId = row.getCellAsNumber(COL_ID).orElse(null)?.toLong()
            val createdAt = row.getCellAsNumber(COL_CREATED_AT).orElse(null)?.toLong()
            val updatedAt = row.getCellAsNumber(COL_UPDATED_AT).orElse(null)?.toLong()
            val provenanceRaw = row.getCellAsString(COL_PROVENANCE).orElse(null)
            val readOnly = provenanceRaw?.endsWith("(read-only)") == true
            val provenance = provenanceRaw
                ?.removeSuffix(" (read-only)")
                ?.let { runCatching { RecordProvenance.valueOf(it) }.getOrNull() }

            val values = mutableMapOf<Long, Any?>()
            val errors = mutableMapOf<Long, String>()
            for ((col, def) in colToDef) {
                if (def.fieldType == FieldType.COMPUTED) continue // never read back - see class doc
                val fieldDefId = def.fieldDefId ?: continue
                val cell = row.getOptionalCell(col)
                if (cell == null || !cell.isPresent) continue // absent cell = no value supplied, not an error
                readFieldCell(row, col, def, fieldDefId, values, errors)
            }
            parsedRows += ParsedRecordRow(
                rowNumber = row.rowNum,
                recordId = recordId,
                createdAt = createdAt,
                updatedAt = updatedAt,
                provenance = provenance,
                provenanceReadOnly = readOnly,
                fieldValues = values,
                fieldParseErrors = errors,
                unmappedHeaders = unmapped,
            )
        }
        return ParsedRecordSheet(recordTypeId, recordTypeName, parsedRows)
    }

    private fun readFieldCell(
        row: org.dhatim.fastexcel.reader.Row,
        col: Int,
        def: ParsedDefinitionRow,
        fieldDefId: Long,
        values: MutableMap<Long, Any?>,
        errors: MutableMap<Long, String>,
    ) {
        when (def.fieldType) {
            FieldType.MONEY_CENTS, FieldType.REFERENCE, FieldType.DATE, FieldType.DATETIME -> {
                val n = row.getCellAsNumber(col).orElse(null)
                if (n != null) values[fieldDefId] = n.toLong() else errors[fieldDefId] =
                    "'${def.fieldName}' needs a whole number, got '${row.getCellText(col)}'"
            }
            FieldType.NUMBER, FieldType.RATING -> {
                val n = row.getCellAsNumber(col).orElse(null)
                if (n != null) values[fieldDefId] = n.toDouble() else errors[fieldDefId] =
                    "'${def.fieldName}' needs a number, got '${row.getCellText(col)}'"
            }
            FieldType.BOOLEAN -> {
                val b = row.getCellAsBoolean(col).orElse(null)
                    ?: row.getCellAsString(col).orElse(null)?.let {
                        when (it.trim().uppercase()) { "TRUE" -> true; "FALSE" -> false; else -> null }
                    }
                if (b != null) values[fieldDefId] = b else errors[fieldDefId] =
                    "'${def.fieldName}' needs TRUE or FALSE, got '${row.getCellText(col)}'"
            }
            FieldType.MULTI_SELECT_CHOICE -> {
                val s = row.getCellAsString(col).orElse(null)
                values[fieldDefId] = s?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList<String>()
            }
            null -> errors[fieldDefId] = "'${def.fieldName}' has an unrecognised field type in the definitions sheet"
            else -> {
                val s = row.getCellAsString(col).orElse(null)
                values[fieldDefId] = s
            }
        }
    }
}
