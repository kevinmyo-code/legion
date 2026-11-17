package com.kevin.legion.backend

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import kotlinx.serialization.json.JsonObject

/**
 * The user-aspect records' half of [HomesSync] (engine-brain ticket 15): the one-time backfill and
 * reconcile of the four tables through `POST <table>/batch/`, the delta push after a local write
 * ([pushChanged], from [HomesWriteThrough.recordsChanged]); the pull of what the engine's own
 * tools wrote is [HomesRecordsPull]. Room stays the replica: nothing here deletes a Room row.
 *
 * Order is the engine's: aspects, record types, fields, entries (a child names its parent). A
 * table is marked done only after a clean pass, so a cut-off run lands on the same rows again.
 * Counts only in the log, one `homes_backfill table=user_*` line per table and one
 * `homes_pull table=user_*` line: never a name, never a value.
 */
object HomesRecordsSync {
    private const val KEY_DONE = "backfill_done_"
    private const val KEY_ID = "rid_"
    private const val KEY_PUSHED = "records_pushed_at"
    private const val CLOCK_MARGIN_MS = 60_000L

    /** Every local row of the four tables, and which of them belong to user aspects. */
    internal class Snapshot(
        val aspects: List<Aspect>,
        val recordTypes: List<RecordType>,
        val fieldDefs: List<FieldDef>,
        val records: List<EngineRecord>,
        remembered: Map<String, String>,
    ) {
        val ids = HomesRecords.IdBook(aspects, recordTypes, fieldDefs, records, remembered)
        val userAspects: Set<Long> = HomesRecords.userAspectIds(aspects, recordTypes, fieldDefs)
        val userTypes: Set<Long> = recordTypes.filter { it.aspectId in userAspects }.map { it.id }.toSet()
        val fieldTypes: Map<Long, FieldType> = fieldDefs.associate { it.id to it.type }

        /** The engine rows of [table], user aspects only; [since] keeps rows changed at or after it. */
        fun rows(table: HomeTable, since: Long = Long.MIN_VALUE): List<JsonObject> = when (table) {
            HomeTable.USER_ASPECTS -> aspects.filter { it.id in userAspects && it.updatedAt >= since }
                .map { HomesRecords.aspect(it, ids) }
            HomeTable.USER_RECORD_TYPES -> recordTypes.filter { it.id in userTypes && it.updatedAt >= since }
                .map { HomesRecords.recordType(it, ids) }
            HomeTable.USER_FIELD_DEFS -> fieldDefs.filter { it.recordTypeId in userTypes && it.updatedAt >= since }
                .map { HomesRecords.fieldDef(it, ids) }
            HomeTable.USER_RECORDS -> records.filter { it.recordTypeId in userTypes && it.updatedAt >= since }
                .map { HomesRecords.record(it, ids, fieldTypes) }
            else -> emptyList()
        }
    }

    internal fun prefs(context: Context) = context.getSharedPreferences(HomesSync.PREFS, Context.MODE_PRIVATE)

    private fun remembered(context: Context): Map<String, String> =
        prefs(context).all.filterKeys { it.startsWith(KEY_ID) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(KEY_ID) to it } }.toMap()

    internal fun remember(context: Context, kind: String, localId: Long, createdAt: Long, guid: String) {
        prefs(context).edit().putString(KEY_ID + HomesRecords.rememberKey(kind, localId, createdAt), guid).apply()
    }

    /** [withRecords] false reads the three small schema tables only: the schema pulls re-read
     * after every insert, and `records` also holds the plugins' replica rows (it can be large). */
    internal suspend fun snapshot(context: Context, withRecords: Boolean = true): Snapshot {
        val db = CarDatabase.getDatabase(context)
        return Snapshot(
            aspects = db.aspectDao().listAll(),
            recordTypes = db.recordTypeDao().listAll(),
            fieldDefs = db.fieldDefDao().listAll(),
            records = if (withRecords) db.engineRecordDao().listAll() else emptyList(),
            remembered = remembered(context),
        )
    }

    /** Pull, then the backfill (first time) or reconcile of the four tables. */
    suspend fun run(context: Context, backend: HomesBackend): List<HomesSync.TableReport> {
        Log.i(HomesSync.TAG, HomesRecordsPull.pull(context, backend).line())
        val snap = snapshot(context)
        val prefs = prefs(context)
        val started = System.currentTimeMillis()
        val reports = HomeTable.RECORDS.map { table ->
            val first = !prefs.getBoolean(KEY_DONE + table.key, false)
            val rows = snap.rows(table)
            val report = HomesSync.pushTable(backend, table, rows.size, rows)
            Log.i(HomesSync.TAG, report.line(first))
            if (first && report.clean) prefs.edit().putBoolean(KEY_DONE + table.key, true).apply()
            report
        }
        if (reports.all { it.clean }) prefs.edit().putLong(KEY_PUSHED, started).apply()
        return reports
    }

    /**
     * The write-through: every user-aspect row changed since the last clean push, in order. The
     * watermark moves only when every table went up clean, so a failure is retried by the next
     * write or the next [run]. A minute of margin covers a row written while the last push read.
     */
    suspend fun pushChanged(context: Context, backend: HomesBackend): List<HomesSync.TableReport> {
        val prefs = prefs(context)
        val since = prefs.getLong(KEY_PUSHED, 0L) - CLOCK_MARGIN_MS
        val started = System.currentTimeMillis()
        val snap = snapshot(context)
        val reports = HomeTable.RECORDS.map { table ->
            val rows = snap.rows(table, since)
            HomesSync.pushTable(backend, table, rows.size, rows)
        }
        if (reports.all { it.clean }) prefs.edit().putLong(KEY_PUSHED, started).apply()
        return reports
    }
}
