package com.kevin.legion.backend.engine

import com.kevin.legion.backend.HomeTable
import com.kevin.legion.backend.HomesBackend
import com.kevin.legion.backend.HomesBatchResult
import com.kevin.legion.backend.HomesFromEngine
import com.kevin.legion.backend.HomesRows
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [HomesBackend] over the engine's homes routes (`server/homes/views.py`, engine-brain ticket 03):
 * the generic synced shape per table (`GET ?since=`, `PUT`/`DELETE <origin_guid>/`) plus
 * `POST <table>/batch/` for the one-time backfill. Rows travel as [JsonObject]s built by
 * `HomesRows`, so a table's wire shape lives in one place.
 */
class DjangoHomesBackend(private val http: EngineHttp) : HomesBackend {

    private fun table(table: HomeTable) = EngineSyncedTable(
        http = http,
        path = table.path,
        rowSerializer = JsonObject.serializer(),
        idOf = { HomesFromEngine.guidOf(it).orEmpty() },
    )

    override suspend fun batch(table: HomeTable, rows: List<JsonObject>): Result<HomesBatchResult> =
        translatingEngineCall("send ${table.key} to the engine") {
            val body = buildJsonObject { put("rows", JsonArray(rows)) }
            val answer = engineSyncedJson.parseToJsonElement(
                http.post("${table.path}batch/", body.toString()).getOrThrow().body,
            ).jsonObject
            fun count(key: String) = answer[key]?.jsonPrimitive?.int ?: 0
            val refused = answer["failed"]?.jsonArray.orEmpty().map { entry ->
                val item = entry.jsonObject
                val fields = (item["detail"] as? JsonObject)?.keys.orEmpty().sorted().joinToString(",")
                "${HomesFromEngine.textOf(item, HomesRows.ORIGIN_GUID) ?: "no-id"}:$fields"
            }
            HomesBatchResult(
                received = count("received"),
                created = count("created"),
                updated = count("updated"),
                unchanged = count("unchanged"),
                failed = refused.size,
                engineCount = count("engine_count"),
                refused = refused,
            )
        }

    override suspend fun put(table: HomeTable, guid: String, row: JsonObject): Result<Unit> =
        translatingEngineCall("save one ${table.key} row") {
            http.put("${table.path}${guid.encodeURLPathPart()}/", row.toString()).getOrThrow()
            Unit
        }

    /** A 404 is success: the engine holds no such row, which is what a clear asks for (a row
     * logged and cleared while offline never reached it). */
    override suspend fun delete(table: HomeTable, guid: String): Result<Unit> {
        val answer = http.delete("${table.path}${guid.encodeURLPathPart()}/")
        val failure = answer.exceptionOrNull() ?: return Result.success(Unit)
        val refused = (failure as? EngineHttpException)?.failure as? EngineFailure.Refused
        return if (refused?.status == HTTP_NOT_FOUND) Result.success(Unit) else Result.failure(failure)
    }

    override suspend fun changedSince(table: HomeTable, sinceIso: String?): Result<List<JsonObject>> =
        translatingEngineCall("load changed ${table.key}") { table(table).fetchChangedSince(sinceIso) }
}
