package com.kevin.midnightai.fleetupload

import com.kevin.midnightai.data.local.CodeEvent
import com.kevin.midnightai.data.local.OdbSample
import com.kevin.midnightai.data.local.Vehicle
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The bytes the head unit sends to `/api/fleet/`, built in one place so a test can pin every
 * field name against the server's serializers (`server/api/fleet.py`: `VehicleSerializer`,
 * `ObdSampleUploadSerializer`, `CodeEventSerializer`). Pure Kotlin, no Android types, so the
 * mapping is exercised on the plain JVM.
 *
 * Ported from the phone's `DjangoFleetDtos.kt` shapes; nothing is shared across modules.
 */
internal object FleetUploadWire {
    /** `api/fleet.py`'s `YEAR_BOUNDS` (`vehicles_year_check`). */
    val YEAR_BOUNDS = 1885..2200

    fun ts(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    /**
     * One OBD sample as the batch route wants it, or null when the server would refuse the whole
     * batch over it (non-finite value, blank pid). The caller skips AND counts such a row, never
     * sends it: one unsendable row would otherwise 400 every batch it sits in, forever.
     */
    fun obdSample(engineVehicleId: String, s: OdbSample): JsonObject? {
        if (!s.value.isFinite() || s.pid.isBlank()) return null
        return buildJsonObject {
            put("vehicle_id", engineVehicleId)
            put("pid", s.pid)
            put("value", s.value)
            put("unit", s.unit)
            put("recorded_at", ts(s.timestamp))
            // lat/lng are optional on the server but a non-finite one is a refusal; null instead.
            put("lat", s.lat?.takeIf { it.isFinite() })
            put("lng", s.lng?.takeIf { it.isFinite() })
        }
    }

    /**
     * The body of `PUT /api/fleet/vehicles/<origin_guid>/`. Odometer is deliberately omitted
     * (the server wants baseline and its timestamp both-or-neither): the head unit's baseline is
     * the driver's word, not something to put on the engine's record. A PUT states the whole row,
     * so this is only ever sent when the row does not exist yet.
     */
    fun vehicleCreate(v: Vehicle, lastObdMac: String?): String = buildJsonObject {
        put("name", v.name)
        put("make", v.make)
        put("model", v.model)
        put("year", v.year)
        put("trim", v.trim.ifBlank { null })
        put("engine", null as String?)
        put("confirmed", v.confirmed)
        // Carry the driver's own flag: archived cars are uploaded too (their history belongs on
        // the engine), and a PUT states the whole row, so false here would un-archive the car.
        put("archived", v.archived)
        put("last_obd_mac", lastObdMac)
    }.toString()

    /** Why a vehicle cannot be created on the engine, or null if it can. */
    fun vehicleCreateRefusal(v: Vehicle): String? = when {
        !v.confirmed -> "it is the unconfirmed placeholder car, not one anyone has named"
        v.name.isBlank() -> "it has no name"
        v.make.isBlank() || v.model.isBlank() -> "it has no make or model"
        v.year !in YEAR_BOUNDS -> "its year (${v.year}) is outside ${YEAR_BOUNDS.first}-${YEAR_BOUNDS.last}"
        else -> null
    }

    /**
     * The stable `sync_id` for a code event. Blank on rows migrated from before the column
     * existed; a deterministic stand-in keeps a retry idempotent instead of minting a fresh id
     * each attempt (which would store the same DTC read twice).
     */
    fun codeEventSyncId(e: CodeEvent): String =
        e.syncId.ifBlank { "headunit:${e.vehicleId}:${e.timestamp}" }

    /**
     * The body of `PUT /api/fleet/code_events/<sync_id>/`, or null if `codesJson` is not a JSON
     * array (the server's `jsonb_typeof = 'array'` CHECK would refuse it). An empty or unreadable
     * freeze frame is sent as null: the server stores null as "the adapter returned none".
     */
    fun codeEvent(engineVehicleId: String, e: CodeEvent): String? {
        val codes = parse(e.codesJson) as? JsonArray ?: return null
        val freeze: JsonElement =
            if (e.freezeFrameJson.isBlank()) JsonNull else (parse(e.freezeFrameJson) as? JsonObject ?: JsonNull)
        return buildJsonObject {
            put("vehicle_id", engineVehicleId)
            put("occurred_at", ts(e.timestamp))
            put("mileage", e.mileage?.takeIf { it >= 0 })
            put("codes", codes)
            put("freeze_frame", freeze)
        }.toString()
    }

    fun batchBody(rows: List<JsonObject>): String = buildJsonArray { rows.forEach { add(it) } }.toString()

    private fun parse(text: String): JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()
}
