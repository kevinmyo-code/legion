package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.backend.engine.DjangoVehiclePlateBackend
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.data.local.CarDatabase

/**
 * What one plate edit did (memory-and-vault ticket 13). Four states, four different sentences,
 * the shape of [com.kevin.legion.purchases.PurchaseOutcome]:
 *
 * - [Ok] - the engine answered 2xx and its row decoded; [plate] is what the engine now holds
 *   (trimmed and upper-cased by the engine, null when cleared). The ONLY state said as saved.
 * - [Unreachable] - nothing was sent. Nothing changed.
 * - [Refused] - the engine said no, or the phone could not address the car; nothing changed.
 * - [Unconfirmed] - a 2xx whose body did not decode: the plate MAY have changed.
 */
sealed interface PlateOutcome {
    data class Ok(val plate: String?) : PlateOutcome
    data class Unreachable(val sentence: String) : PlateOutcome
    data class Refused(val sentence: String) : PlateOutcome
    data class Unconfirmed(val sentence: String) : PlateOutcome
}

/** The seam [VehiclePlateController] writes through; a test fakes it. One implementation:
 * [DjangoVehiclePlateBackend]. */
interface VehiclePlateBackend {
    /** Sets the engine's `vehicles.plate` on the car with server id [serverId]; null clears it. */
    suspend fun setPlate(serverId: String, plate: String?): PlateOutcome
}

/** The phone's copy of which car is in front of the user and what plate it has. */
interface VehiclePlateStore {
    /** The engine id of the car [vehicleId] (the phone's `obdMac` key), or null when the car has
     * never reached the engine. */
    suspend fun serverIdFor(vehicleId: String): String?

    suspend fun plateFor(serverId: String): String?

    /** Writes the engine's accepted value into the local replica. */
    suspend fun savePlate(serverId: String, plate: String?)
}

/** The car the Specs screen's plate pane is about. [serverId] null: not on the engine yet. */
data class PlateTarget(val serverId: String?, val plate: String?)

/**
 * The licence plate on a vehicle (memory-and-vault ticket 13, Kevin's ruling 5: a plate is
 * ordinary data, not an ID-vault item). The Specs screen edits it through here, and the engine is
 * the authority on the value: this class only refuses a plate too long to be one, before sending,
 * so the person hears why at once. A plain class with its backend and store injected (CLAUDE.md
 * section 8).
 *
 * **No plate is ever written in code or tests** (map ruling 6): Kevin sets his own on the phone or
 * through the API.
 */
class VehiclePlateController(
    private val backend: VehiclePlateBackend,
    private val store: VehiclePlateStore,
) {
    suspend fun target(vehicleId: String): PlateTarget {
        val serverId = store.serverIdFor(vehicleId) ?: return PlateTarget(null, null)
        return PlateTarget(serverId, store.plateFor(serverId))
    }

    /** Saves [typed] as the plate of [serverId]. Blank clears it. */
    suspend fun setPlate(serverId: String, typed: String): PlateOutcome {
        val cleaned = normalise(typed)
        if (cleaned != null && cleaned.length > PLATE_MAX_CHARS) {
            return PlateOutcome.Refused(
                "A plate is at most $PLATE_MAX_CHARS characters; that one is ${cleaned.length}. Nothing was changed.",
            )
        }
        val outcome = backend.setPlate(serverId, cleaned)
        if (outcome is PlateOutcome.Ok) store.savePlate(serverId, outcome.plate)
        return outcome
    }

    companion object {
        /** The engine's `api/vehicle_plate.PLATE_MAX_CHARS`. The engine re-checks; this only words it early. */
        const val PLATE_MAX_CHARS = 16

        /** Blank is "no plate"; inner spacing collapses to one space, as the engine does. */
        fun normalise(typed: String): String? = typed.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ").uppercase().ifEmpty { null }

        /** What the screen says after an edit. */
        fun sentence(outcome: PlateOutcome): String = when (outcome) {
            is PlateOutcome.Ok -> outcome.plate?.let { "Saved on the engine: the plate is $it." }
                ?: "Saved on the engine: this car has no plate on file now."
            is PlateOutcome.Unreachable -> "Can't reach the engine, so nothing was changed. ${outcome.sentence}"
            is PlateOutcome.Refused -> outcome.sentence
            is PlateOutcome.Unconfirmed ->
                "The engine answered but its reply could not be read, so the plate may or may not have changed. " +
                    outcome.sentence
        }

        fun forContext(context: Context): VehiclePlateController {
            val app = context.applicationContext
            return VehiclePlateController(
                DjangoVehiclePlateBackend(EngineHttp(EngineConfig(app))),
                RoomVehiclePlateStore(CarDatabase.getDatabase(app)),
            )
        }

        /** The plate of the car [vehicleId] as the phone last heard it from the engine, or null. For
         * a fleet tool that is asked for it. */
        suspend fun plateOf(context: Context, vehicleId: String): String? {
            val store = RoomVehiclePlateStore(CarDatabase.getDatabase(context.applicationContext))
            return store.serverIdFor(vehicleId)?.let { store.plateFor(it) }
        }
    }
}

/** [VehiclePlateStore] over the sidecar (which car) and the replica (its plate). */
class RoomVehiclePlateStore(private val db: CarDatabase) : VehiclePlateStore {
    override suspend fun serverIdFor(vehicleId: String): String? =
        db.vehicleSidecarDao().getByMac(vehicleId)?.serverId

    override suspend fun plateFor(serverId: String): String? =
        db.vehicleReplicaDao().getByServerId(serverId)?.plate

    override suspend fun savePlate(serverId: String, plate: String?) {
        val row = db.vehicleReplicaDao().getByServerId(serverId) ?: return
        db.vehicleReplicaDao().update(row.copy(plate = plate))
    }
}
