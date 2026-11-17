package com.kevin.legion.backend

import android.content.Context
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
 * What the engine's own tools wrote to the user-aspect records (engine-brain ticket 15), brought
 * into Room by `?since=`: aspects, record types, fields (then each type's primary-field pointers),
 * then entries through [RecordStore], the only write door for records, under the engine's guid.
 *
 * Each engine row is first read into a [Head] (identity, clocks, parent). A row missing one of
 * those is [Applied.SKIPPED] and counted; the cursors then stay where they were, so it is read
 * again next time rather than stepped over. A row removed on the engine leaves Room's copy alone.
 */
@Suppress("TooManyFunctions") // one apply per table, plus the head reader and the matcher they share
internal object HomesRecordsPull {
    private const val KEY_CURSOR = "pull_since_"

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

    enum class Applied { INSERTED, UPDATED, SAME, SKIPPED }

    /** An engine row's identity, clocks and parent guid; null when any is missing. */
    private class Head(val guid: String, val name: String, val parent: String?, val born: Long, val changed: Long)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.ms(key: String): Long? = text(key)?.let(HomesFromEngine::parseMs)

    private fun head(row: JsonObject, parentKey: String?): Head? = runCatching {
        Head(
            guid = requireNotNull(HomesFromEngine.guidOf(row)),
            name = row.text("name").orEmpty(),
            parent = parentKey?.let { requireNotNull(row.text(it)) },
            born = requireNotNull(row.ms("created_at_client")),
            changed = requireNotNull(row.ms("updated_at_client")),
        )
    }.getOrNull()

    private fun tally(results: List<Applied>) = PullReport(
        reached = true,
        inserted = results.count { it == Applied.INSERTED },
        updated = results.count { it == Applied.UPDATED },
        skipped = results.count { it == Applied.SKIPPED },
    )

    /** Fetches all four feeds first; nothing is applied unless all four answered. */
    suspend fun pull(context: Context, backend: HomesBackend): PullReport {
        val prefs = HomesRecordsSync.prefs(context)
        val feeds = HomeTable.RECORDS.associateWith { table ->
            backend.changedSince(table, prefs.getString(KEY_CURSOR + table.key, null)).getOrNull()
        }
        if (feeds.values.any { it == null }) return PullReport(reached = false)
        val rows = feeds.mapValues { (_, v) -> v.orEmpty() }
        val live = rows.mapValues { (_, v) -> v.filter { HomesFromEngine.textOf(it, "deleted_at") == null } }
        val results = mutableListOf<Applied>()
        live.getValue(HomeTable.USER_ASPECTS)
            .forEach { results += aspect(context, it, guids(live, HomeTable.USER_ASPECTS)) }
        live.getValue(HomeTable.USER_RECORD_TYPES)
            .forEach { results += recordType(context, it, guids(live, HomeTable.USER_RECORD_TYPES)) }
        live.getValue(HomeTable.USER_FIELD_DEFS)
            .forEach { results += fieldDef(context, it, guids(live, HomeTable.USER_FIELD_DEFS)) }
        live.getValue(HomeTable.USER_RECORD_TYPES).forEach { primaryFields(context, it) }
        results += records(context, rows.getValue(HomeTable.USER_RECORDS))
        val report = tally(results)
        if (report.skipped == 0) {
            rows.forEach { (table, list) ->
                list.mapNotNull { HomesFromEngine.textOf(it, "updated_at") }.maxOrNull()
                    ?.let { prefs.edit().putString(KEY_CURSOR + table.key, it).apply() }
            }
        }
        return report
    }

    private fun guids(rows: Map<HomeTable, List<JsonObject>>, table: HomeTable): Set<String> =
        rows.getValue(table).mapNotNull(HomesFromEngine::guidOf).toSet()

    /**
     * The local row an engine row is: by guid ([HomesRecords.IdBook]), else (a forgotten
     * remembered guid, or another phone's row) the local row born at the same instant with the
     * same name, else the one with the same name under the same parent that the engine does not
     * already know by another guid. Null: insert.
     */
    private fun <T> match(
        head: Head,
        byGuid: Map<String, Long>,
        candidates: List<Pair<T, Long>>,
        known: Known<T>,
    ): Long? {
        val named = candidates.filter { (row, _) -> known.name(row).equals(head.name, ignoreCase = true) }
        return byGuid[head.guid]
            ?: named.firstOrNull { (row, _) -> known.born(row) == head.born }?.second
            ?: named.firstOrNull { (_, id) -> known.guid(id) !in known.engine }?.second
    }

    /** How [match] reads one kind of local row. */
    private class Known<T>(
        val name: (T) -> String,
        val born: (T) -> Long,
        val guid: (Long) -> String?,
        val engine: Set<String>,
    )

    private suspend fun aspect(context: Context, row: JsonObject, engine: Set<String>): Applied {
        val head = head(row, parentKey = null) ?: return Applied.SKIPPED
        val dao = CarDatabase.getDatabase(context).aspectDao()
        val snap = HomesRecordsSync.snapshot(context, withRecords = false)
        val known = Known<Aspect>({ it.name }, { it.createdAt }, { snap.ids.aspectGuid[it] }, engine)
        val local = match(head, snap.ids.aspectId, snap.aspects.map { it to it.id }, known)
        val incoming = Aspect(
            name = head.name,
            icon = row.text("icon").orEmpty(),
            color = row.text("color").orEmpty(),
            position = (row["position"] as? JsonPrimitive)?.intOrNull ?: 0,
            archived = (row["archived"] as? JsonPrimitive)?.booleanOrNull ?: false,
            archivedAt = row.ms("archived_at"),
            createdAt = head.born,
            updatedAt = head.changed,
        )
        val mine = snap.aspects.firstOrNull { it.id == local }
        return when {
            mine == null -> {
                HomesRecordsSync.remember(context, HomesRecords.ASPECT, dao.insert(incoming), head.born, head.guid)
                Applied.INSERTED
            }
            else -> {
                adopt(context, HomesRecords.ASPECT, mine.id, mine.createdAt, head.guid, snap.ids.aspectGuid)
                if (head.changed > mine.updatedAt) {
                    dao.update(incoming.copy(id = mine.id, createdAt = mine.createdAt))
                    Applied.UPDATED
                } else {
                    Applied.SAME
                }
            }
        }
    }

    /** Remembers the engine's guid for a local row it matched by instant or name. */
    private fun adopt(context: Context, kind: String, id: Long, born: Long, guid: String, mine: Map<Long, String>) {
        if (mine[id] != guid) HomesRecordsSync.remember(context, kind, id, born, guid)
    }

    @Suppress("ReturnCount") // a skip, an insert, an unchanged row, then the update: one exit each
    private suspend fun recordType(context: Context, row: JsonObject, engine: Set<String>): Applied {
        val snap = HomesRecordsSync.snapshot(context, withRecords = false)
        val head = head(row, parentKey = "aspect")
        val aspectId = head?.parent?.let { snap.ids.aspectId[it] }
        if (head == null || aspectId == null) return Applied.SKIPPED
        val dao = CarDatabase.getDatabase(context).recordTypeDao()
        val known = Known<RecordType>({ it.name }, { it.createdAt }, { snap.ids.recordTypeGuid[it] }, engine)
        val siblings = snap.recordTypes.filter { it.aspectId == aspectId }.map { it to it.id }
        val mine = match(head, snap.ids.recordTypeId, siblings, known)
            ?.let { id -> snap.recordTypes.first { it.id == id } }
        if (mine == null) {
            val fresh = RecordType(
                aspectId = aspectId, name = head.name, createdAt = head.born, updatedAt = head.changed,
            )
            val id = dao.insert(fresh)
            HomesRecordsSync.remember(context, HomesRecords.RECORD_TYPE, id, head.born, head.guid)
            return Applied.INSERTED
        }
        adopt(context, HomesRecords.RECORD_TYPE, mine.id, mine.createdAt, head.guid, snap.ids.recordTypeGuid)
        if (head.changed <= mine.updatedAt) return Applied.SAME
        dao.update(mine.copy(name = head.name, updatedAt = head.changed))
        return Applied.UPDATED
    }

    @Suppress("ReturnCount") // a skip, an insert, an unchanged row, then the update: one exit each
    private suspend fun fieldDef(context: Context, row: JsonObject, engine: Set<String>): Applied {
        val snap = HomesRecordsSync.snapshot(context, withRecords = false)
        val head = head(row, parentKey = "record_type")
        val typeId = head?.parent?.let { snap.ids.recordTypeId[it] }
        val type = row.text("type")?.let { t -> FieldType.entries.firstOrNull { it.name == t } }
        if (head == null || typeId == null || type == null) return Applied.SKIPPED
        val dao = CarDatabase.getDatabase(context).fieldDefDao()
        val known = Known<FieldDef>({ it.name }, { it.createdAt }, { snap.ids.fieldGuid[it] }, engine)
        val siblings = snap.fieldDefs.filter { it.recordTypeId == typeId }.map { it to it.id }
        val mine = match(head, snap.ids.fieldId, siblings, known)
            ?.let { id -> snap.fieldDefs.first { it.id == id } }
        val config = HomesRecords.configIn(row.text("config"), snap.ids)
        val required = (row["required"] as? JsonPrimitive)?.booleanOrNull
        val position = (row["position"] as? JsonPrimitive)?.intOrNull
        if (mine == null) {
            val fresh = FieldDef(
                recordTypeId = typeId, name = head.name, type = type, required = required ?: false,
                position = position ?: 0, config = config, createdAt = head.born, updatedAt = head.changed,
            )
            HomesRecordsSync.remember(context, HomesRecords.FIELD_DEF, dao.insert(fresh), head.born, head.guid)
            return Applied.INSERTED
        }
        adopt(context, HomesRecords.FIELD_DEF, mine.id, mine.createdAt, head.guid, snap.ids.fieldGuid)
        if (head.changed <= mine.updatedAt || mine.locked) return Applied.SAME
        dao.update(
            mine.copy(
                name = head.name, required = required ?: mine.required, position = position ?: mine.position,
                config = config, updatedAt = head.changed,
            ),
        )
        return Applied.UPDATED
    }

    /** A record type's two primary-field pointers, once its fields are local. */
    private suspend fun primaryFields(context: Context, row: JsonObject) {
        val snap = HomesRecordsSync.snapshot(context, withRecords = false)
        val mine = HomesFromEngine.guidOf(row)?.let { snap.ids.recordTypeId[it] }
            ?.let { id -> snap.recordTypes.firstOrNull { it.id == id } } ?: return
        val amount = row.text("primary_amount_field")?.let { snap.ids.fieldId[it] } ?: mine.primaryAmountFieldId
        val due = row.text("primary_due_date_field")?.let { snap.ids.fieldId[it] } ?: mine.primaryDueDateFieldId
        if (amount != mine.primaryAmountFieldId || due != mine.primaryDueDateFieldId) {
            CarDatabase.getDatabase(context).recordTypeDao()
                .update(mine.copy(primaryAmountFieldId = amount, primaryDueDateFieldId = due))
        }
    }

    /** One engine entry as [RecordStore] takes it. */
    private class Entry(val head: Head, val typeId: Long, val values: Map<Long, Any?>, val row: JsonObject) {
        val trashed = row.text("trashed_at") != null || row.text("deleted_at") != null
        val provenance = RecordProvenance.entries.firstOrNull { it.name == row.text("provenance") }
            ?: RecordProvenance.USER
    }

    /** Entries through [RecordStore] (the only write door for records), under their own guid. */
    private suspend fun records(context: Context, rows: List<JsonObject>): List<Applied> {
        val db = CarDatabase.getDatabase(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val snap = HomesRecordsSync.snapshot(context)
        return rows.map { row ->
            val head = head(row, parentKey = "record_type")
            val typeId = head?.parent?.let { snap.ids.recordTypeId[it] }
            val fields = snap.fieldDefs.filter { it.recordTypeId == typeId }
            val values = HomesRecords.storeValues(row["payload"] as? JsonObject, snap.ids, fields)
            if (head == null || typeId == null || values == null) {
                Applied.SKIPPED
            } else {
                record(store, db.engineRecordDao().getByGuid(head.guid), Entry(head, typeId, values, row))
            }
        }
    }

    private suspend fun record(store: RecordStore, mine: EngineRecord?, entry: Entry): Applied = when {
        mine == null && entry.trashed -> Applied.SAME
        mine == null -> {
            val made = store.create(
                entry.typeId, entry.values, entry.provenance,
                now = entry.head.born, guid = entry.head.guid, updatedAt = entry.head.changed,
            )
            if (made is RecordStore.WriteResult.Success) Applied.INSERTED else Applied.SKIPPED
        }
        entry.head.changed <= mine.updatedAt -> Applied.SAME
        entry.trashed ->
            if (store.delete(mine.id) == RecordStore.DeleteResult.Trashed) Applied.UPDATED else Applied.SAME
        else -> {
            if (mine.deletedAt != null) store.restore(mine.id)
            val done = store.update(mine.id, entry.values) is RecordStore.WriteResult.Success
            if (done) Applied.UPDATED else Applied.SKIPPED
        }
    }
}
