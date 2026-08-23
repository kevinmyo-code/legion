package com.kevin.legion.engine

import com.kevin.legion.data.local.AspectDao
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.EngineRecordDao
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldDefDao
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.data.local.RecordTypeDao
import org.json.JSONObject

/**
 * The read side the eight [WidgetKind]s draw from (aspect-engine ticket 18) - [RecordStore]'s own
 * write door has no read counterpart of its own (every existing caller reads the DAOs directly,
 * per that class's doc comment), so this is the FIRST read layer the engine gets, purpose-built for
 * "what does a dashboard widget need to show", not a general query API. Every function is pure
 * read-only (no writes anywhere in this file) and every function's result type states its own
 * empty/error/not-configured case explicitly, in words, rather than an empty list a caller cannot
 * tell apart from "still loading" or "genuinely nothing there yet" - the same "unreadable and empty
 * are different sentences" posture CLAUDE.md §1 states for calendar reads, applied here to the
 * engine's own tables where the equivalent failure is a deleted field def or record type, not a
 * revoked OS permission.
 */
class WidgetDataSource(
    private val engineRecordDao: EngineRecordDao,
    private val fieldDefDao: FieldDefDao,
    private val recordTypeDao: RecordTypeDao,
    private val aspectDao: AspectDao,
) {
    // ---- stat tile --------------------------------------------------------------------------------

    sealed class StatResult {
        data class Count(val n: Int) : StatResult()
        data class Money(val cents: Long) : StatResult()
        data class Number(val value: Double) : StatResult()
        /** No [WidgetConfig][com.kevin.legion.ui.widgets.WidgetConfig] fieldId/recordTypeId set yet -
         * the seeded default state, never rendered as a fabricated zero. */
        object NotConfigured : StatResult()
        data class Error(val message: String) : StatResult()
    }

    /** A live COUNT of [recordTypeId]'s active records when [fieldId] is null; the SUM of [fieldId]
     * across them (MONEY_CENTS or NUMBER/RATING only) when it is set. */
    suspend fun statTile(recordTypeId: Long?, fieldId: Long?): StatResult {
        if (recordTypeId == null) return StatResult.NotConfigured
        val active = engineRecordDao.activeByRecordType(recordTypeId)
        if (fieldId == null) return StatResult.Count(active.size)
        val fieldDefs = fieldDefDao.forRecordType(recordTypeId)
        val fd = fieldDefs.firstOrNull { it.id == fieldId }
            ?: return StatResult.Error("the configured field was deleted")
        return when (fd.type) {
            FieldType.MONEY_CENTS -> {
                val total = active.sumOf { PayloadCodec.readLong(JSONObject(it.payload), fieldId) ?: 0L }
                StatResult.Money(total)
            }
            FieldType.NUMBER, FieldType.RATING -> {
                val total = active.sumOf { PayloadCodec.readDouble(JSONObject(it.payload), fieldId) ?: 0.0 }
                StatResult.Number(total)
            }
            FieldType.COMPUTED -> {
                val err = active.firstNotNullOfOrNull { PayloadCodec.readComputedError(JSONObject(it.payload), fieldId) }
                if (err != null) return StatResult.Error(err)
                val total = active.sumOf { PayloadCodec.readDouble(JSONObject(it.payload), fieldId) ?: 0.0 }
                StatResult.Number(total)
            }
            else -> StatResult.Error("'${fd.name}' is not a summable field type")
        }
    }

    // ---- record list / single record --------------------------------------------------------------

    data class ListRow(val recordId: Long, val title: String, val value: String)

    /** The most recently updated [limit] active records of [recordTypeId], each reduced to a title
     * ([titleFor]) and a value column (the record's own promoted `amountCents` formatted as a dollar
     * string if present, else `dueAt` as a plain date, else blank). `null` recordTypeId or a deleted
     * type both return `null` - the caller renders "not configured"/"deleted" distinctly from an
     * empty (but real) list, which returns as `emptyList()`. */
    suspend fun recordList(recordTypeId: Long?, limit: Int): List<ListRow>? {
        if (recordTypeId == null) return null
        val recordType = recordTypeDao.getById(recordTypeId) ?: return null
        val fieldDefs = fieldDefDao.forRecordType(recordTypeId)
        val active = engineRecordDao.activeByRecordType(recordTypeId).sortedByDescending { it.updatedAt }.take(limit)
        return active.map { r -> ListRow(r.id, titleFor(fieldDefs, JSONObject(r.payload), r.id), valueFor(r)) }
    }

    data class RecordCard(val title: String, val rows: List<Pair<String, String>>, val provenance: RecordProvenance)

    /** One specific record's card: title plus every TEXT/NUMBER/MONEY_CENTS/CHOICE field rendered as
     * a label/value pair, and the record's own [RecordProvenance] (CLAUDE.md §4 rule 4/7 - provenance
     * in words on every surface, generated or hand-built). `null` when [recordId] is null, deleted,
     * or trashed - a caller distinguishes "not configured" from "record vanished" via its own copy,
     * both of which read this same `null`, since either way there is nothing left to show. */
    suspend fun singleRecord(recordId: Long?): RecordCard? {
        if (recordId == null) return null
        val record = engineRecordDao.getById(recordId) ?: return null
        if (record.deletedAt != null) return null
        val fieldDefs = fieldDefDao.forRecordType(record.recordTypeId)
        val payload = JSONObject(record.payload)
        val rows = fieldDefs
            .filter { it.type in listOf(FieldType.TEXT, FieldType.NUMBER, FieldType.MONEY_CENTS, FieldType.CHOICE, FieldType.DATE) }
            .mapNotNull { fd -> renderField(fd, payload)?.let { fd.name to it } }
        return RecordCard(titleFor(fieldDefs, payload, record.id), rows, record.provenance)
    }

    // ---- next-due / agenda --------------------------------------------------------------------------

    data class DueItem(val recordId: Long, val title: String, val dueAt: Long)

    /** The single soonest-due active record, at or after [now] - scoped to [recordTypeId] if given,
     * across EVERY record type that declares a `primaryDueDateFieldId` otherwise (the cross-aspect
     * "what's next" case ticket 08's brief names). `null` = genuinely nothing due, not an error. */
    suspend fun nextDue(recordTypeId: Long?, now: Long = System.currentTimeMillis()): DueItem? =
        dueItemsAcross(recordTypeId, now).minByOrNull { it.dueAt }

    /** Every active record due at or after [now], soonest first, capped at [limit] - the AGENDA
     * widget's own feed. Same scope rule as [nextDue]. An empty list here is a genuine "nothing
     * scheduled" - the widget's own copy states that in words rather than rendering blank space. */
    suspend fun agenda(recordTypeId: Long?, limit: Int, now: Long = System.currentTimeMillis()): List<DueItem> =
        dueItemsAcross(recordTypeId, now).sortedBy { it.dueAt }.take(limit)

    private suspend fun dueItemsAcross(recordTypeId: Long?, now: Long): List<DueItem> {
        val types: List<RecordType> = if (recordTypeId != null) {
            listOfNotNull(recordTypeDao.getById(recordTypeId))
        } else {
            // No "list every record type" DAO method exists (ticket 16 never needed one) - this
            // widget is the first caller that needs to scan across types, so it goes through
            // whatever aspects exist rather than assuming a table-wide query is available.
            allRecordTypes()
        }
        val out = mutableListOf<DueItem>()
        for (rt in types) {
            val dueFieldId = rt.primaryDueDateFieldId ?: continue
            val fieldDefs = fieldDefDao.forRecordType(rt.id)
            val active = engineRecordDao.activeByRecordType(rt.id)
            for (r in active) {
                val due = r.dueAt ?: continue
                if (due < now) continue
                out += DueItem(r.id, titleFor(fieldDefs, JSONObject(r.payload), r.id), due)
            }
        }
        return out
    }

    /** Every record type across every ACTIVE (non-archived) aspect - [nextDue]/[agenda]'s cross-type
     * scan needs this and nothing else in the engine has needed a table-wide read before now.
     * [RecordTypeDao] has no "list all" query (ticket 16 scoped it per-aspect only), so this fans out
     * over [AspectDao.listActive] instead - on every real device today that list is empty (see
     * [DefaultArrangementSeeder]'s own doc: nothing has migrated onto the engine yet), so this costs
     * one cheap query and returns empty rather than guessing at ids or scanning a table that does not
     * exist. */
    private suspend fun allRecordTypes(): List<RecordType> =
        aspectDao.listActive().flatMap { recordTypeDao.listByAspect(it.id) }

    // ---- chart --------------------------------------------------------------------------------------

    data class ChartPoint(val xMs: Long, val y: Float)

    /** [fieldId] plotted against [dateFieldId] across [recordTypeId]'s active records, x-ascending,
     * capped at the most recent [limit]. `null` = not configured (either field id missing or the
     * field itself no longer exists) - distinct from an empty list (configured, but zero records
     * carry a value for either field yet). */
    suspend fun chartSeries(recordTypeId: Long?, fieldId: Long?, dateFieldId: Long?, limit: Int): List<ChartPoint>? {
        if (recordTypeId == null || fieldId == null || dateFieldId == null) return null
        val fieldDefs = fieldDefDao.forRecordType(recordTypeId)
        val yField = fieldDefs.firstOrNull { it.id == fieldId } ?: return null
        val xField = fieldDefs.firstOrNull { it.id == dateFieldId } ?: return null
        if (xField.type != FieldType.DATE && xField.type != FieldType.DATETIME) return null
        val active = engineRecordDao.activeByRecordType(recordTypeId)
        val points = active.mapNotNull { r ->
            val payload = JSONObject(r.payload)
            val x = PayloadCodec.readLong(payload, xField.id) ?: return@mapNotNull null
            val y = when (yField.type) {
                FieldType.MONEY_CENTS -> PayloadCodec.readLong(payload, yField.id)?.toFloat()
                FieldType.NUMBER, FieldType.RATING -> PayloadCodec.readDouble(payload, yField.id)?.toFloat()
                else -> null
            } ?: return@mapNotNull null
            ChartPoint(x, y)
        }.sortedBy { it.xMs }
        return points.takeLast(limit.coerceAtLeast(1))
    }

    // ---- photo --------------------------------------------------------------------------------------

    /** The stored path for [recordId]'s [fieldId] PHOTO field, or `null` if not configured, the
     * record/field is gone, or no photo was ever attached - the composable that renders this decides
     * which of those words to say, since only it knows which case applies (see
     * `ui/widgets/EngineWidgets.kt`'s `PhotoWidget`). */
    suspend fun photoPath(recordId: Long?, fieldId: Long?): String? {
        if (recordId == null || fieldId == null) return null
        val record = engineRecordDao.getById(recordId) ?: return null
        if (record.deletedAt != null) return null
        return PayloadCodec.readString(JSONObject(record.payload), fieldId)
    }

    // ---- shared helpers -------------------------------------------------------------------------------

    /** The first TEXT field's value, or the first CHOICE field's, or `Record #<id>` - a record type
     * is never GUARANTEED to have a title-shaped field (a plain tally type might be all numbers), so
     * the id fallback is the honest floor rather than an empty string. */
    private fun titleFor(fieldDefs: List<FieldDef>, payload: JSONObject, recordId: Long): String {
        val textField = fieldDefs.firstOrNull { it.type == FieldType.TEXT }
            ?: fieldDefs.firstOrNull { it.type == FieldType.CHOICE }
        val value = textField?.let { PayloadCodec.readString(payload, it.id) }
        return if (!value.isNullOrBlank()) value else "Record #$recordId"
    }

    private fun valueFor(r: EngineRecord): String = when {
        r.amountCents != null -> formatCents(r.amountCents)
        r.dueAt != null -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(r.dueAt))
        else -> ""
    }

    private fun renderField(fd: FieldDef, payload: JSONObject): String? = when (fd.type) {
        FieldType.MONEY_CENTS -> PayloadCodec.readLong(payload, fd.id)?.let { formatCents(it) }
        FieldType.NUMBER, FieldType.RATING -> PayloadCodec.readDouble(payload, fd.id)?.toString()
        FieldType.TEXT, FieldType.CHOICE -> PayloadCodec.readString(payload, fd.id)
        FieldType.DATE, FieldType.DATETIME -> PayloadCodec.readLong(payload, fd.id)
            ?.let { java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(it)) }
        else -> null
    }

    /** Cents to a plain `$x.xx` string - `Long` cents in, never a `Double` (CLAUDE.md §4 rule 3),
     * division is only for DISPLAY formatting, never for a stored or compared value. */
    private fun formatCents(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }
}
