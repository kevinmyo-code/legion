package com.kevin.legion.backend.engine

import com.kevin.legion.vehicle.PlateOutcome
import com.kevin.legion.vehicle.VehiclePlateBackend
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val VEHICLES_PATH = "/api/fleet/vehicles/"

/**
 * The plate edit over the engine's vehicle route (memory-and-vault ticket 13; the engine half is
 * ticket 06, `server/api/fleet.py` `VehicleSerializer.plate`).
 *
 * **Why a read before the write.** `PUT /api/fleet/vehicles/<origin_guid>/` is the only write the
 * route takes, it is keyed on `origin_guid` and it INSERTS when that guid is unknown; and a PUT
 * that omits `confirmed` or `archived` resets them to false. So the phone first reads the engine's
 * live rows, finds the car by its server id, and PUTs back EVERY field exactly as the engine holds
 * it, with only `plate` changed. It never PUTs to a guid the engine did not hand it, so it can never
 * create a car, and a car with no `origin_guid` (made on the engine directly) is refused in words.
 *
 * `plate` is always sent here, null included (an explicit null clears it). The migration upload
 * ([DjangoFleetBackend.uploadMigratedVehicle], [DjangoVehicleWrite]) never names `plate`, so it
 * keeps the stored one, which is the engine's rule for an omitted field.
 */
class DjangoVehiclePlateBackend(private val http: EngineHttp) : VehiclePlateBackend {

    private val vehicles = EngineSyncedTable(http, VEHICLES_PATH, DjangoVehicleRow.serializer()) { it.id }

    override suspend fun setPlate(serverId: String, plate: String?): PlateOutcome {
        val rows = try {
            vehicles.fetchActive()
        } catch (e: EngineHttpException) {
            return readFailure(e.failure)
        } catch (e: SerializationException) {
            return unreadableList(e)
        } catch (e: IllegalArgumentException) {
            return unreadableList(e)
        }
        val row = rows.firstOrNull { it.id == serverId }
            ?: return PlateOutcome.Refused("The engine has no live car with this one's id. Nothing was changed.")
        val guid = row.originGuid?.takeIf { it.isNotBlank() }
            ?: return PlateOutcome.Refused(NO_GUID)
        return write(guid, putBody(row, plate))
    }

    private suspend fun write(guid: String, body: String): PlateOutcome {
        val result = http.put("$VEHICLES_PATH${guid.encodeURLPathPart()}/", body)
        result.getOrNull()?.let { ok ->
            return try {
                PlateOutcome.Ok(engineSyncedJson.decodeFromString(DjangoVehicleRow.serializer(), ok.body).plate)
            } catch (e: SerializationException) {
                PlateOutcome.Unconfirmed("The reply did not decode: ${e.message}")
            } catch (e: IllegalArgumentException) {
                PlateOutcome.Unconfirmed("The reply did not decode: ${e.message}")
            }
        }
        return when (val failure = (result.exceptionOrNull() as? EngineHttpException)?.failure) {
            is EngineFailure.Unreachable -> PlateOutcome.Unreachable(failure.message)
            is EngineFailure.Refused -> PlateOutcome.Refused(
                "The engine refused the plate: ${engineRefusalSentence(failure.body)} Nothing was changed.",
            )
            is EngineFailure.Unauthorized -> PlateOutcome.Refused(failure.sentence)
            is EngineFailure.Malformed -> PlateOutcome.Unconfirmed(failure.sentence)
            null -> PlateOutcome.Unconfirmed(result.exceptionOrNull()?.message ?: "unknown error")
        }
    }

    private fun readFailure(failure: EngineFailure): PlateOutcome = when (failure) {
        is EngineFailure.Unreachable -> PlateOutcome.Unreachable(failure.message)
        is EngineFailure.Refused -> PlateOutcome.Refused(
            "The engine would not list the cars: ${engineRefusalSentence(failure.body)} Nothing was changed.",
        )
        else -> PlateOutcome.Refused("${failure.sentence} Nothing was changed.")
    }

    private fun unreadableList(e: Exception): PlateOutcome =
        PlateOutcome.Refused("The engine's list of cars could not be read (${e.message}). Nothing was changed.")

    internal companion object {
        const val NO_GUID =
            "This car was created on the engine directly, so the phone cannot address it; nothing was changed."

        /** Every field as the engine holds it, plus [plate]. Built by hand so a null is sent as null. */
        fun putBody(row: DjangoVehicleRow, plate: String?): String = JsonObject(
            mapOf(
                "name" to JsonPrimitive(row.name),
                "make" to JsonPrimitive(row.make),
                "model" to JsonPrimitive(row.model),
                "year" to JsonPrimitive(row.year),
                "trim" to (row.trim?.let(::JsonPrimitive) ?: JsonNull),
                "engine" to (row.engine?.let(::JsonPrimitive) ?: JsonNull),
                "confirmed" to JsonPrimitive(row.confirmed),
                "odometer_baseline" to (row.odometerBaseline?.let(::JsonPrimitive) ?: JsonNull),
                "odometer_baseline_at" to (row.odometerBaselineAt?.let(::JsonPrimitive) ?: JsonNull),
                "archived" to JsonPrimitive(row.archived),
                "last_obd_mac" to (row.lastObdMac?.let(::JsonPrimitive) ?: JsonNull),
                "plate" to (plate?.let(::JsonPrimitive) ?: JsonNull),
            ),
        ).toString()
    }
}
