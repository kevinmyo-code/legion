package com.kevin.legion.backend.engine

import com.kevin.legion.backend.ChecklistChanges
import com.kevin.legion.backend.ChecklistFields
import com.kevin.legion.backend.ChecklistItemFields
import com.kevin.legion.backend.ChecklistsBackend
import com.kevin.legion.backend.RemoteChecklist
import com.kevin.legion.backend.RemoteChecklistItem
import com.kevin.legion.backend.RemoteChecklistTick
import java.time.OffsetDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CHECKLISTS_PATH = "/api/checklists/"
private const val CHANGES_PATH = "/api/changes"

private val checklistJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

private fun parseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

@Serializable
private data class ChecklistRow(
    val id: String,
    val name: String,
    @SerialName("schedule_kind") val scheduleKind: String? = null,
    @SerialName("schedule_every") val scheduleEvery: Int? = null,
    @SerialName("schedule_days_of_week") val scheduleDaysOfWeek: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val archived: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("sync_id") val syncId: String? = null,
) {
    fun toRemote() = RemoteChecklist(
        serverId = id,
        syncId = syncId,
        name = name,
        scheduleKind = scheduleKind,
        scheduleEvery = scheduleEvery,
        scheduleDaysOfWeek = scheduleDaysOfWeek,
        sortOrder = sortOrder,
        archived = archived,
        createdAtMs = parseTs(createdAt),
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

@Serializable
private data class ChecklistItemRow(
    val id: String,
    val checklist: String,
    val text: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("sync_id") val syncId: String? = null,
    @SerialName("measure_unit") val measureUnit: String? = null,
    @SerialName("measure_target") val measureTarget: Double? = null,
    @SerialName("measure_direction") val measureDirection: String? = null,
) {
    fun toRemote() = RemoteChecklistItem(
        serverId = id,
        syncId = syncId,
        checklistServerId = checklist,
        text = text,
        sortOrder = sortOrder,
        createdAtMs = parseTs(createdAt),
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        measureUnit = measureUnit,
        measureTarget = measureTarget,
        measureDirection = measureDirection,
    )
}

@Serializable
private data class ChecklistTickRow(
    val id: String,
    val item: String,
    val day: Int,
    @SerialName("ticked_at") val tickedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("sync_id") val syncId: String? = null,
    val value: Double? = null,
    val source: String = "USER_REPORTED",
) {
    fun toRemote() = RemoteChecklistTick(
        serverId = id,
        syncId = syncId,
        itemServerId = item,
        day = day,
        tickedAtMs = parseTs(tickedAt),
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        value = value,
        source = source,
    )
}

/**
 * `GET /api/changes?aspects=checklists`'s body. All four keys are present in a real response
 * (measured against the live engine 2026-09-06: `{"server_time":..., "checklists":[],
 * "checklist_items":[], "checklist_ticks":[]}` on an engine whose checklist tables are still
 * empty), and the three lists default to empty anyway so a future response that omits one cannot
 * fail to decode.
 */
@Serializable
private data class ChangesBody(
    @SerialName("server_time") val serverTime: String,
    val checklists: List<ChecklistRow> = emptyList(),
    @SerialName("checklist_items") val checklistItems: List<ChecklistItemRow> = emptyList(),
    @SerialName("checklist_ticks") val checklistTicks: List<ChecklistTickRow> = emptyList(),
)

/**
 * `POST`/`PATCH` body for a checklist. Every field is REQUIRED (no `= null` default) and
 * [checklistJson] sets `explicitNulls = true`, so a clear-to-null (dropping a schedule,
 * unarchiving) genuinely clears server-side instead of leaving the old value in place on DRF's
 * `partial=True` patch - the same rule `SupabaseEventsBackend`'s own `EventUpsertDto` doc comment
 * states at length for the identical reason.
 *
 * `sync_id` is typed nullable only because the COLUMN is; this client states the row's real value
 * on a create AND on a patch. **Corrected 2026-09-06 before it ever ran: an earlier draft of this
 * comment said a patch "never changes it", and the code behind it passed `null` - which, with
 * `explicitNulls`, would have sent `"sync_id": null` and wiped the row's idempotency key, the one
 * thing a later outbox retry needs to find it by.**
 */
@Serializable
private data class ChecklistWrite(
    val name: String,
    @SerialName("schedule_kind") val scheduleKind: String?,
    @SerialName("schedule_every") val scheduleEvery: Int?,
    @SerialName("schedule_days_of_week") val scheduleDaysOfWeek: String?,
    @SerialName("sort_order") val sortOrder: Int,
    val archived: Boolean,
    @SerialName("sync_id") val syncId: String?,
)

@Serializable
private data class ChecklistItemWrite(
    val text: String,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("sync_id") val syncId: String?,
    @SerialName("measure_unit") val measureUnit: String?,
    @SerialName("measure_target") val measureTarget: Double?,
    @SerialName("measure_direction") val measureDirection: String?,
)

/** `POST .../tick` body. Exactly the three fields `TickRequestSerializer` declares - no `sync_id`,
 * because that serializer has no such field (see [RemoteChecklistTick]'s own doc comment). */
@Serializable
private data class TickWrite(val day: Int, val value: Double?, val source: String)

/**
 * [ChecklistsBackend] over the household Django engine (`server/checklists/views.py`,
 * `server/checklists/urls.py`, `server/api/changes.py`). The first sync path checklists have ever
 * had - `.scratch/django-engine/research/execution-plan.md` Phase 2 step 4, "Checklists get
 * ChecklistsSync for the first time, against Django tables Django owns."
 *
 * **Every write is idempotent by something the caller controls**, which is what makes an outbox
 * retry safe: a checklist/item create carries `sync_id` (`_idempotent_or_none` returns the
 * existing row), a patch is a patch, a delete is idempotent server-side, and a tick is unique on
 * `(item, day)` with a documented revive-on-retick. Draining the same queued entry twice can
 * therefore never produce a second row.
 *
 * **Paths carry no trailing slash except the collection root.** `checklists/urls.py`'s list route
 * is `""` under an `api/checklists/` include, so the combined pattern is `api/checklists/`
 * exactly, while every id-suffixed route has none - the same asymmetry `tests/test_checklists_api.py`
 * documents in its own `_make_checklist` helper. Getting this wrong is a 404, not a redirect.
 */
class DjangoChecklistsBackend(private val http: EngineHttp) : ChecklistsBackend {

    // translating/prefix/decode used to be private members here and byte-identical copies in
    // DjangoEventsBackend; they now live once in EngineCalls.kt. That move was not tidying: with
    // them here this class had thirteen functions, past detekt's per-class ceiling of eleven,
    // which this ticket's brief forbids baselining away. The Refused-passes-through-unprefixed
    // rule that matters most here (the measured-tick refusal must reach a screen in the engine's
    // own words) is stated in prefixEngineFailure's own doc comment.
    private fun <T> decode(serializer: KSerializer<T>, body: String): T =
        checklistJson.decodeFromString(serializer, body)

    override suspend fun fetchChanges(sinceIso: String?): Result<ChecklistChanges> =
        translatingEngineCall("load your checklists") {
            val query = buildMap {
                put("aspects", "checklists")
                sinceIso?.let { put("since", it) }
            }
            val body = decode(ChangesBody.serializer(), http.get(CHANGES_PATH, query).getOrThrow().body)
            ChecklistChanges(
                serverTime = body.serverTime,
                checklists = body.checklists.map { it.toRemote() },
                items = body.checklistItems.map { it.toRemote() },
                ticks = body.checklistTicks.map { it.toRemote() },
            )
        }

    override suspend fun upsertChecklist(syncId: String, fields: ChecklistFields): Result<RemoteChecklist> =
        translatingEngineCall("save that checklist") {
            val body = checklistJson.encodeToString(ChecklistWrite.serializer(), fields.toWrite(syncId))
            decode(ChecklistRow.serializer(), http.post(CHECKLISTS_PATH, body).getOrThrow().body).toRemote()
        }

    /** `sync_id` is re-sent, never omitted - `explicitNulls` puts every field on the wire (so a
     * clear-to-null actually clears), which means an omitted `sync_id` would be sent as a literal
     * null and WIPE the row's idempotency key. See [ChecklistsBackend.patchChecklist]'s own doc. */
    override suspend fun patchChecklist(
        serverId: String,
        syncId: String,
        fields: ChecklistFields,
    ): Result<RemoteChecklist> =
        translatingEngineCall("update that checklist") {
            val body = checklistJson.encodeToString(ChecklistWrite.serializer(), fields.toWrite(syncId))
            decode(
                ChecklistRow.serializer(),
                http.patch(CHECKLISTS_PATH + serverId, body).getOrThrow().body,
            ).toRemote()
        }

    override suspend fun deleteChecklist(serverId: String): Result<Boolean> =
        deletingEngineRow("remove that checklist") { http.delete(CHECKLISTS_PATH + serverId) }

    override suspend fun upsertItem(
        checklistServerId: String,
        syncId: String,
        fields: ChecklistItemFields,
    ): Result<RemoteChecklistItem> = translatingEngineCall("save that checklist item") {
        val body = checklistJson.encodeToString(ChecklistItemWrite.serializer(), fields.toWrite(syncId))
        decode(
            ChecklistItemRow.serializer(),
            http.post("$CHECKLISTS_PATH$checklistServerId/items", body).getOrThrow().body,
        ).toRemote()
    }

    override suspend fun patchItem(
        checklistServerId: String,
        itemServerId: String,
        syncId: String,
        fields: ChecklistItemFields,
    ): Result<RemoteChecklistItem> = translatingEngineCall("update that checklist item") {
        val body = checklistJson.encodeToString(ChecklistItemWrite.serializer(), fields.toWrite(syncId))
        decode(
            ChecklistItemRow.serializer(),
            http.patch("$CHECKLISTS_PATH$checklistServerId/items/$itemServerId", body).getOrThrow().body,
        ).toRemote()
    }

    override suspend fun deleteItem(checklistServerId: String, itemServerId: String): Result<Boolean> =
        deletingEngineRow("remove that checklist item") {
            http.delete("$CHECKLISTS_PATH$checklistServerId/items/$itemServerId")
        }

    override suspend fun tick(
        checklistServerId: String,
        itemServerId: String,
        day: Int,
        value: Double?,
        source: String,
    ): Result<RemoteChecklistTick> = translatingEngineCall("record that tick") {
        val body = checklistJson.encodeToString(TickWrite.serializer(), TickWrite(day, value, source))
        decode(
            ChecklistTickRow.serializer(),
            http.post("$CHECKLISTS_PATH$checklistServerId/items/$itemServerId/tick", body).getOrThrow().body,
        ).toRemote()
    }

    override suspend fun untick(checklistServerId: String, itemServerId: String, day: Int): Result<Boolean> =
        deletingEngineRow("undo that tick") {
            http.delete("$CHECKLISTS_PATH$checklistServerId/items/$itemServerId/tick/$day")
        }

}

private fun ChecklistFields.toWrite(syncId: String) = ChecklistWrite(
    name = name,
    scheduleKind = scheduleKind,
    scheduleEvery = scheduleEvery,
    scheduleDaysOfWeek = scheduleDaysOfWeek,
    sortOrder = sortOrder,
    archived = archived,
    syncId = syncId,
)

private fun ChecklistItemFields.toWrite(syncId: String) = ChecklistItemWrite(
    text = text,
    sortOrder = sortOrder,
    syncId = syncId,
    measureUnit = measureUnit,
    measureTarget = measureTarget,
    measureDirection = measureDirection,
)
