package com.kevin.legion.backend

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.RecordStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The user-aspect records' half of [HomesSync] (engine-brain ticket 15): the one-time backfill and
 * reconcile of the four tables through `POST <table>/batch/`, the delta push after a local write
 * ([pushChanged], from [HomesWriteThrough.recordsChanged]), and the pull of what the engine's own
 * tools wrote. Room stays the replica: nothing here deletes a Room row.
 *
 * Order is the engine's: aspects, record types, fields, entries (a child names its parent). A
 * table is marked done only after a clean pass, so a cut-off run lands on the same rows again.
 * Counts only in the log, one `homes_backfill table=user_*` line per table and one
 * `homes_pull table=user_*` line: never a name, never a value.
 */
object HomesRecordsSync {
    private const val KEY_DONE = "backfill_done_"
    private const val KEY_ID = "rid_"
    private const val KEY_CURSOR = "pull_since_"
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

    private fun prefs(context: Context) = context.getSharedPreferences(HomesSync.PREFS, Context.MODE_PRIVATE)

    private fun remembered(context: Context): Map<String, String> =
        prefs(context).all.filterKeys { it.startsWith(KEY_ID) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(KEY_ID) to it } }.toMap()

    private fun remember(context: Context, kind: String, localId: Long, guid: String) {
        prefs(context).edit().putString("$KEY_ID$kind:$localId", guid).apply()
    }

    internal suspend fun snapshot(context: Context): Snapshot {
        val db = CarDatabase.getDatabase(context)
        return Snapshot(
            aspects = db.aspectDao().listAll(),
            recordTypes = db.recordTypeDao().listAll(),
            fieldDefs = db.fieldDefDao().listAll(),
            records = db.engineRecordDao().listAll(),
            remembered = remembered(context),
        )
    }

    /** Pull, then the backfill (first time) or reconcile of the four tables. */
    suspend fun run(context: Context, backend: HomesBackend): List<HomesSync.TableReport> {
        Log.i(HomesSync.TAG, pull(context, backend).line())
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

    // ---- pull --------------------------------------------------------------------------------

    /** What one pull changed in Room, in counts only. */
    data class PullReport(
        val reached: Boolean,
        val inserted: Int = 0,
        val updated: Int = 0,
        val skipped: Int = 0,
    ) {
        fun line(): String =
            "homes_pull table=user_records reached=$reached inserted=$inserted updated=$updated skipped=$skipped"

        operator fun plus(o: PullReport) =
            PullReport(reached && o.reached, inserted + o.inserted, updated + o.updated, skipped + o.skipped)
    }

    private fun cursor(context: Context, table: HomeTable): String? =
        prefs(context).getString(KEY_CURSOR + table.key, null)

    private fun advance(context: Context, table: HomeTable, rows: List<JsonObject>) {
        val newest = rows.mapNotNull { HomesFromEngine.textOf(it, "updated_at") }.maxOrNull() ?: return
        prefs(context).edit().putString(KEY_CURSOR + table.key, newest).apply()
    }

    /** Fetches all four feeds first; nothing is applied unless all four answered. */
    internal suspend fun pull(context: Context, backend: HomesBackend): PullReport {
        val feeds = HomeTable.RECORDS.associateWith { table ->
            backend.changedSince(table, cursor(context, table)).getOrElse { return PullReport(reached = false) }
        }
        var report = PullReport(reached = true)
        report += pullAspects(context, feeds.getValue(HomeTable.USER_ASPECTS))
        report += pullRecordTypes(context, feeds.getValue(HomeTable.USER_RECORD_TYPES))
        report += pullFieldDefs(context, feeds.getValue(HomeTable.USER_FIELD_DEFS))
        report += pullPrimaryFields(context, feeds.getValue(HomeTable.USER_RECORD_TYPES))
        report += pullRecords(context, feeds.getValue(HomeTable.USER_RECORDS))
        // A skipped row (a parent this phone lacks, a field it cannot name) is read again next
        // time rather than stepped over for good.
        if (report.skipped == 0) feeds.forEach { (table, rows) -> advance(context, table, rows) }
        return report
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.ms(key: String): Long? = text(key)?.let(HomesFromEngine::parseMs)
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    /**
     * The local row an engine row is: by guid ([HomesRecords.IdBook]), else (a forgotten
     * remembered guid, or another phone's row) the local row born at the same instant with the
     * same name, else the one with the same name under the same parent that the engine does not
     * already know by another guid. Null: insert.
     */
    private fun <T> match(
        guid: String,
        byGuid: Map<String, Long>,
        candidates: List<T>,
        engineGuids: Set<String>,
        guidOf: (T) -> String?,
        same: (T) -> Boolean,
        bornTogether: (T) -> Boolean,
        idOf: (T) -> Long,
    ): Long? = byGuid[guid]
        ?: candidates.firstOrNull { same(it) && bornTogether(it) }?.let(idOf)
        ?: candidates.firstOrNull { same(it) && guidOf(it) !in engineGuids }?.let(idOf)

    private suspend fun pullAspects(context: Context, rows: List<JsonObject>): PullReport {
        val dao = CarDatabase.getDatabase(context).aspectDao()
        var snap = snapshot(context)
        var inserted = 0
        var updated = 0
        var skipped = 0
        val engineGuids = rows.mapNotNull(HomesFromEngine::guidOf).toSet()
        for (row in rows) {
            val guid = HomesFromEngine.guidOf(row) ?: continue
            val name = row.text("name")
            val born = row.ms("created_at_client")
            val changed = row.ms("updated_at_client")
            if (row.text("deleted_at") != null) continue // an aspect is archived, never removed
            if (name == null || born == null || changed == null) {
                skipped++
                continue
            }
            val local = match(
                guid, snap.ids.aspectId, snap.aspects, engineGuids,
                guidOf = { snap.ids.aspectGuid[it.id] },
                same = { it.name.equals(name, ignoreCase = true) },
                bornTogether = { it.createdAt == born },
                idOf = { it.id },
            )
            val incoming = Aspect(
                name = name,
                icon = row.text("icon").orEmpty(),
                color = row.text("color").orEmpty(),
                position = row.int("position") ?: 0,
                archived = row.bool("archived") ?: false,
                archivedAt = row.ms("archived_at"),
                createdAt = born,
                updatedAt = changed,
            )
            if (local == null) {
                remember(context, HomesRecords.ASPECT, dao.insert(incoming), guid)
                inserted++
            } else {
                if (snap.ids.aspectGuid[local] != guid) remember(context, HomesRecords.ASPECT, local, guid)
                val mine = snap.aspects.first { it.id == local }
                if (changed > mine.updatedAt) {
                    dao.update(incoming.copy(id = local, createdAt = mine.createdAt))
                    updated++
                }
            }
            snap = snapshot(context)
        }
        return PullReport(true, inserted, updated, skipped)
    }

    private suspend fun pullRecordTypes(context: Context, rows: List<JsonObject>): PullReport {
        val dao = CarDatabase.getDatabase(context).recordTypeDao()
        var snap = snapshot(context)
        var inserted = 0
        var updated = 0
        var skipped = 0
        val engineGuids = rows.mapNotNull(HomesFromEngine::guidOf).toSet()
        for (row in rows) {
            val guid = HomesFromEngine.guidOf(row) ?: continue
            val name = row.text("name")
            val aspectId = row.text("aspect")?.let { snap.ids.aspectId[it] }
            val born = row.ms("created_at_client")
            val changed = row.ms("updated_at_client")
            if (row.text("deleted_at") != null) continue // removed on the engine: Room keeps its copy
            if (name == null || aspectId == null || born == null || changed == null) {
                skipped++
                continue
            }
            val siblings = snap.recordTypes.filter { it.aspectId == aspectId }
            val local = match(
                guid, snap.ids.recordTypeId, siblings, engineGuids,
                guidOf = { snap.ids.recordTypeGuid[it.id] },
                same = { it.name.equals(name, ignoreCase = true) },
                bornTogether = { it.createdAt == born },
                idOf = { it.id },
            )
            if (local == null) {
                val id = dao.insert(RecordType(aspectId = aspectId, name = name, createdAt = born, updatedAt = changed))
                remember(context, HomesRecords.RECORD_TYPE, id, guid)
                inserted++
            } else {
                if (snap.ids.recordTypeGuid[local] != guid) remember(context, HomesRecords.RECORD_TYPE, local, guid)
                val mine = snap.recordTypes.first { it.id == local }
                if (changed > mine.updatedAt) {
                    dao.update(mine.copy(name = name, updatedAt = changed))
                    updated++
                }
            }
            snap = snapshot(context)
        }
        return PullReport(true, inserted, updated, skipped)
    }

    private suspend fun pullFieldDefs(context: Context, rows: List<JsonObject>): PullReport {
        val dao = CarDatabase.getDatabase(context).fieldDefDao()
        var snap = snapshot(context)
        var inserted = 0
        var updated = 0
        var skipped = 0
        val engineGuids = rows.mapNotNull(HomesFromEngine::guidOf).toSet()
        for (row in rows) {
            val guid = HomesFromEngine.guidOf(row) ?: continue
            val name = row.text("name")
            val typeId = row.text("record_type")?.let { snap.ids.recordTypeId[it] }
            val type = row.text("type")?.let { t -> FieldType.entries.firstOrNull { it.name == t } }
            val born = row.ms("created_at_client")
            val changed = row.ms("updated_at_client")
            if (row.text("deleted_at") != null) continue // removed on the engine: Room keeps its copy
            if (name == null || typeId == null || type == null || born == null || changed == null) {
                skipped++
                continue
            }
            val siblings = snap.fieldDefs.filter { it.recordTypeId == typeId }
            val local = match(
                guid, snap.ids.fieldId, siblings, engineGuids,
                guidOf = { snap.ids.fieldGuid[it.id] },
                same = { it.name.equals(name, ignoreCase = true) },
                bornTogether = { it.createdAt == born },
                idOf = { it.id },
            )
            val config = HomesRecords.configIn(row.text("config"), snap.ids)
            if (local == null) {
                val id = dao.insert(
                    FieldDef(
                        recordTypeId = typeId,
                        name = name,
                        type = type,
                        required = row.bool("required") ?: false,
                        position = row.int("position") ?: 0,
                        config = config,
                        createdAt = born,
                        updatedAt = changed,
                    ),
                )
                remember(context, HomesRecords.FIELD_DEF, id, guid)
                inserted++
            } else {
                if (snap.ids.fieldGuid[local] != guid) remember(context, HomesRecords.FIELD_DEF, local, guid)
                val mine = snap.fieldDefs.first { it.id == local }
                if (changed > mine.updatedAt && !mine.locked) {
                    dao.update(
                        mine.copy(
                            name = name,
                            required = row.bool("required") ?: mine.required,
                            position = row.int("position") ?: mine.position,
                            config = config,
                            updatedAt = changed,
                        ),
                    )
                    updated++
                }
            }
            snap = snapshot(context)
        }
        return PullReport(true, inserted, updated, skipped)
    }

    /** A record type's two primary-field pointers, once its fields are local. */
    private suspend fun pullPrimaryFields(context: Context, rows: List<JsonObject>): PullReport {
        val dao = CarDatabase.getDatabase(context).recordTypeDao()
        val snap = snapshot(context)
        for (row in rows) {
            val local = HomesFromEngine.guidOf(row)?.let { snap.ids.recordTypeId[it] } ?: continue
            val mine = snap.recordTypes.first { it.id == local }
            val amount = row.text("primary_amount_field")?.let { snap.ids.fieldId[it] }
            val due = row.text("primary_due_date_field")?.let { snap.ids.fieldId[it] }
            if ((amount != null && amount != mine.primaryAmountFieldId) || (due != null && due != mine.primaryDueDateFieldId)) {
                dao.update(
                    mine.copy(
                        primaryAmountFieldId = amount ?: mine.primaryAmountFieldId,
                        primaryDueDateFieldId = due ?: mine.primaryDueDateFieldId,
                    ),
                )
            }
        }
        return PullReport(true)
    }

    /** An engine entry through [RecordStore] (the only write door for records), under its own guid. */
    private suspend fun pullRecords(context: Context, rows: List<JsonObject>): PullReport {
        val db = CarDatabase.getDatabase(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val snap = snapshot(context)
        var inserted = 0
        var updated = 0
        var skipped = 0
        for (row in rows) {
            val guid = HomesFromEngine.guidOf(row) ?: continue
            val typeId = row.text("record_type")?.let { snap.ids.recordTypeId[it] }
            val born = row.ms("created_at_client")
            val changed = row.ms("updated_at_client")
            val fields = snap.fieldDefs.filter { it.recordTypeId == typeId }
            val values = HomesRecords.storeValues(row["payload"] as? JsonObject, snap.ids, fields)
            if (typeId == null || born == null || changed == null || values == null) {
                skipped++
                continue
            }
            val trashed = row.text("trashed_at") != null || row.text("deleted_at") != null
            val mine = db.engineRecordDao().getByGuid(guid)
            when {
                mine == null && trashed -> Unit
                mine == null -> {
                    val provenance = RecordProvenance.entries.firstOrNull { it.name == row.text("provenance") }
                        ?: RecordProvenance.USER
                    when (store.create(typeId, values, provenance, now = born, guid = guid, updatedAt = changed)) {
                        is RecordStore.WriteResult.Success -> inserted++
                        is RecordStore.WriteResult.Failure -> skipped++
                    }
                }
                changed <= mine.updatedAt -> Unit
                trashed && mine.deletedAt == null ->
                    if (store.delete(mine.id) == RecordStore.DeleteResult.Trashed) updated++ else skipped++
                else -> {
                    if (mine.deletedAt != null) store.restore(mine.id)
                    if (store.update(mine.id, values) is RecordStore.WriteResult.Success) updated++ else skipped++
                }
            }
        }
        return PullReport(true, inserted, updated, skipped)
    }
}
