package com.kevin.midnightai.fleetupload

import com.kevin.midnightai.backend.engine.EngineFailure
import com.kevin.midnightai.backend.engine.EngineHttp
import com.kevin.midnightai.backend.engine.EngineHttpException
import com.kevin.midnightai.data.local.CodeEvent
import com.kevin.midnightai.data.local.Drive
import com.kevin.midnightai.data.local.OdbSample
import com.kevin.midnightai.data.local.Vehicle
import com.kevin.midnightai.diag.DriveLog
import com.kevin.midnightai.diag.UploadStatusSink
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Why a car-data upload ran. Only for the breadcrumb; the engine is not told. */
enum class FleetUploadReason { DRIVE_END, STARTUP, PERIODIC, MANUAL }

/** What an attempt did, in words the Settings row shows as-is. */
sealed interface FleetUploadOutcome {
    val sentence: String

    data object NothingToSend : FleetUploadOutcome {
        override val sentence = "No car data waiting to upload."
    }

    /** No engine address or token: everything stays on the unit, nothing was sent. */
    data object NoEngine : FleetUploadOutcome {
        override val sentence = "Not signed in to the engine, so car data stays on this unit. Nothing was sent."
    }

    /** [notes] are per-car sentences for cars that were skipped, e.g. an ambiguous match. */
    data class Sent(
        val samples: Int,
        val codeEvents: Int,
        val moreWaiting: Boolean,
        val notes: List<String>,
        val drives: Int = 0,
    ) : FleetUploadOutcome {
        override val sentence: String get() = buildString {
            append("Uploaded $samples OBD sample${if (samples == 1) "" else "s"}")
            append(", $codeEvents code event${if (codeEvents == 1) "" else "s"}")
            append(" and $drives drive${if (drives == 1) "" else "s"}.")
            if (moreWaiting) append(" More is still waiting and will go on the next upload.")
            notes.forEach { append(' ').append(it) }
        }
    }

    /** Some cars were skipped and nothing else was sent. Not a failure to reach the engine. */
    data class Skipped(val notes: List<String>) : FleetUploadOutcome {
        override val sentence: String get() = "Nothing sent. " + notes.joinToString(" ")
    }

    data class Failed(
        val reason: String,
        val samples: Int,
        val codeEvents: Int,
    ) : FleetUploadOutcome {
        override val sentence = "Car data upload failed: $reason It is kept here and will be retried."
    }
}

/** How much is waiting, for the Settings row. */
data class FleetPending(val samples: Int, val codeEvents: Int, val drives: Int = 0)

/** The head unit's own tables, behind a seam so the logic runs on the plain JVM. */
interface FleetUploadSource {
    suspend fun vehicles(): List<Vehicle>
    suspend fun samplesAfter(vehicleId: String, afterId: Long, limit: Int): List<OdbSample>
    suspend fun sampleCountAfter(vehicleId: String, afterId: Long): Int
    suspend fun codeEventsAfter(vehicleId: String, afterId: Long, limit: Int): List<CodeEvent>
    suspend fun codeEventCountAfter(vehicleId: String, afterId: Long): Int
    suspend fun drivesAfter(vehicleId: String, afterId: Long, limit: Int): List<Drive>
    suspend fun driveCountAfter(vehicleId: String, afterId: Long): Int
}

/**
 * What has already been sent, and which engine vehicle each local car is. Kept in a small prefs
 * store rather than a Room column: it is upload bookkeeping, not data, and a schema change would
 * need a migration for a flag nothing else reads.
 *
 * Cursors are the highest local row id that got a 2xx, per car and per table. They only ever
 * move forward and are advanced AFTER the response, never before.
 */
interface FleetCursorStore {
    fun sampleCursor(vehicleId: String): Long
    fun setSampleCursor(vehicleId: String, id: Long)
    fun codeEventCursor(vehicleId: String): Long
    fun setCodeEventCursor(vehicleId: String, id: Long)
    fun driveCursor(vehicleId: String): Long
    fun setDriveCursor(vehicleId: String, id: Long)
    fun engineVehicleId(vehicleId: String): String?
    fun setEngineVehicleId(vehicleId: String, engineId: String)
    fun clearEngineVehicleId(vehicleId: String)
}

/** Which engine vehicle a head-unit car is. Refusals say why in words; none of them guesses. */
sealed interface VehicleResolution {
    data class Resolved(val engineId: String, val created: Boolean) : VehicleResolution

    /** More than one engine vehicle claims this car. Skipped, never picked between. */
    data class Ambiguous(val engineIds: List<String>) : VehicleResolution

    /** No engine vehicle matches and this one may not be created, or the lookup did not finish. */
    data class Unresolved(val sentence: String) : VehicleResolution

    /** The engine could not be reached or refused; the whole run should stop. */
    data class Failed(val failure: EngineFailure) : VehicleResolution
}

/**
 * Sends the head unit's OBD samples and DTC code events to the household engine, the same
 * `/api/fleet/` routes the phone writes (2026-10-17, Kevin: the dongle is back on the head unit).
 * Upload only; nothing is pulled.
 *
 * **Vehicle identity is the risk, so it is resolved, never invented.** The phone derives an engine
 * vehicle's `origin_guid` from the car's id (`FleetRecordBridge.vehicleGuid`:
 * `UUID.nameUUIDFromBytes("fleet-vehicle:<id>")`) and stamps `last_obd_mac` on it. This reads the
 * engine's vehicle list and matches on either, ignoring soft-deleted rows. Exactly one distinct
 * match is the car. Two or more is [VehicleResolution.Ambiguous]: that car's upload is skipped and
 * a breadcrumb says so. No match creates the vehicle at the SAME deterministic `origin_guid`, so a
 * phone that later creates "the same" car lands on the row this made instead of a second one.
 * `last_obd_mac` is a hint on the server ("never an identity"); it is trusted here only when it
 * agrees with at most one row, and a guid match and a different hint-only row is ambiguous.
 *
 * **A row is marked sent only after a 2xx.** Any other outcome, an exception included, leaves the
 * cursor where it was. The server's insert is `on conflict do nothing` on
 * `(vehicle_id, pid, recorded_at)` and code events are a PUT keyed by `sync_id`, so a 2xx lost on
 * the way back and a retry store each row once.
 *
 * **Drives** come from the head unit's own `drives` table (v13), written by
 * `TelemetryRecorder.finalizeDrive` with the start time the recorder actually saw, and go up as
 * `PUT /api/fleet/drives/<sync_id>/`. Drives before v13 are not backfilled: reconstructing them
 * from the `TRIP_MILES` marker samples would need an inferred start. The daily drive log is a
 * narrative rollup and is not a drive. Code clear events do not exist on the head unit: it never
 * sends Mode 04.
 *
 * Offline or not signed in sends nothing, loses nothing, and says so in the drive log.
 */
class FleetUploader(
    private val source: FleetUploadSource,
    private val cursors: FleetCursorStore,
    private val http: EngineHttp,
    private val status: UploadStatusSink,
    private val log: (String) -> Unit = DriveLog::breadcrumb,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxBatches: Int = MAX_BATCHES_PER_RUN,
) {
    suspend fun pending(): FleetPending {
        var samples = 0
        var codes = 0
        var drives = 0
        for (v in source.vehicles()) {
            drives += source.driveCountAfter(v.obdMac, cursors.driveCursor(v.obdMac))
            samples += source.sampleCountAfter(v.obdMac, cursors.sampleCursor(v.obdMac))
            codes += source.codeEventCountAfter(v.obdMac, cursors.codeEventCursor(v.obdMac))
        }
        return FleetPending(samples, codes, drives)
    }

    suspend fun upload(reason: FleetUploadReason): FleetUploadOutcome = lock.withLock {
        val outcome = try {
            run(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Last-resort net: a bug or a database error must not cost the cursor or crash the
            // service. Nothing was marked sent past the last 2xx.
            log("car data upload crashed: ${e.javaClass.simpleName}: ${e.message}")
            FleetUploadOutcome.Failed("something went wrong on this unit (${e.javaClass.simpleName}).", 0, 0)
        }
        if (outcome !is FleetUploadOutcome.NothingToSend) {
            status.record(now(), ok = outcome is FleetUploadOutcome.Sent, sentence = outcome.sentence)
        }
        outcome
    }

    private suspend fun run(reason: FleetUploadReason): FleetUploadOutcome {
        val vehicles = source.vehicles()
        val waiting = vehicles.filter {
            source.sampleCountAfter(it.obdMac, cursors.sampleCursor(it.obdMac)) > 0 ||
                source.codeEventCountAfter(it.obdMac, cursors.codeEventCursor(it.obdMac)) > 0 ||
                source.driveCountAfter(it.obdMac, cursors.driveCursor(it.obdMac)) > 0
        }
        if (waiting.isEmpty()) return FleetUploadOutcome.NothingToSend
        if (!http.isUsable()) {
            log("car data upload (${reason.name.lowercase()}): not signed in to the engine; data stays on this unit")
            return FleetUploadOutcome.NoEngine
        }
        var samplesSent = 0
        var codesSent = 0
        var drivesSent = 0
        var moreWaiting = false
        val notes = mutableListOf<String>()
        var listing: List<EngineVehicle>? = null
        for (vehicle in waiting) {
            val resolution = resolve(vehicle, listing) { listing = it }
            when (resolution) {
                is VehicleResolution.Failed -> return failed(resolution.failure.sentence, samplesSent, codesSent)
                is VehicleResolution.Ambiguous -> {
                    val note = "Skipped ${vehicle.name.ifBlank { "a car" }}: ${resolution.engineIds.size} vehicles on " +
                        "the engine claim the same dongle, so nothing was uploaded for it. Fix the duplicate on the engine."
                    log("car data upload: $note")
                    notes += note
                }
                is VehicleResolution.Unresolved -> {
                    val note = "Skipped ${vehicle.name.ifBlank { "a car" }}: ${resolution.sentence}"
                    log("car data upload: $note")
                    notes += note
                }
                is VehicleResolution.Resolved -> {
                    val sent = sendCar(vehicle, resolution.engineId)
                    samplesSent += sent.samples
                    codesSent += sent.codeEvents
                    drivesSent += sent.drives
                    moreWaiting = moreWaiting || sent.moreWaiting
                    sent.failure?.let { return failed(it, samplesSent, codesSent) }
                }
            }
        }
        if (samplesSent == 0 && codesSent == 0 && drivesSent == 0) {
            return if (notes.isEmpty()) FleetUploadOutcome.NothingToSend else FleetUploadOutcome.Skipped(notes)
        }
        log("car data upload (${reason.name.lowercase()}): sent $samplesSent samples, $codesSent code events, $drivesSent drives")
        return FleetUploadOutcome.Sent(samplesSent, codesSent, moreWaiting, notes, drivesSent)
    }

    private fun failed(reason: String, samples: Int, codes: Int): FleetUploadOutcome {
        val trimmed = reason.take(MAX_REASON_CHARS)
        log("car data upload failed after $samples samples and $codes code events: $trimmed")
        return FleetUploadOutcome.Failed(trimmed, samples, codes)
    }

    // --- identity ------------------------------------------------------------------------------

    /** The engine's `vehicles` row, trimmed to what matching needs. */
    internal data class EngineVehicle(val id: String, val originGuid: String?, val lastObdMac: String?, val deleted: Boolean)

    private suspend fun resolve(
        vehicle: Vehicle,
        cachedListing: List<EngineVehicle>?,
        remember: (List<EngineVehicle>) -> Unit,
    ): VehicleResolution {
        val key = vehicle.obdMac
        cursors.engineVehicleId(key)?.let { return VehicleResolution.Resolved(it, created = false) }
        val listing = cachedListing ?: when (val fetched = fetchVehicles()) {
            is Fetched.Rows -> fetched.rows.also(remember)
            is Fetched.Incomplete ->
                return VehicleResolution.Unresolved("the engine's vehicle list could not be read in full, so no car was matched.")
            is Fetched.Error -> return VehicleResolution.Failed(fetched.failure)
        }
        val guid = vehicleGuid(key)
        val mac = key.takeIf { isDongleMac(it) }?.uppercase()
        val live = listing.filter { !it.deleted }
        val matches = live.filter { row ->
            row.originGuid.equals(guid, ignoreCase = true) ||
                (mac != null && row.lastObdMac?.uppercase() == mac)
        }.map { it.id }.distinct()
        return when {
            matches.size == 1 -> VehicleResolution.Resolved(matches.single(), created = false).also {
                cursors.setEngineVehicleId(key, it.engineId)
            }
            matches.size > 1 -> VehicleResolution.Ambiguous(matches)
            listing.any { it.deleted && it.originGuid.equals(guid, ignoreCase = true) } ->
                VehicleResolution.Unresolved("its vehicle was deleted on the engine, so it was not recreated.")
            else -> create(vehicle, guid, mac, remember, listing)
        }
    }

    private suspend fun create(
        vehicle: Vehicle,
        guid: String,
        mac: String?,
        remember: (List<EngineVehicle>) -> Unit,
        listing: List<EngineVehicle>,
    ): VehicleResolution {
        FleetUploadWire.vehicleCreateRefusal(vehicle)?.let {
            return VehicleResolution.Unresolved("no matching vehicle on the engine and ${it}, so none was created.")
        }
        val result = http.put("/api/fleet/vehicles/$guid/", FleetUploadWire.vehicleCreate(vehicle, mac))
        val ok = result.getOrNull()
            ?: return VehicleResolution.Failed(failureOf(result.exceptionOrNull()))
        val id = runCatching { Json.parseToJsonElement(ok.body).jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        if (id.isNullOrBlank()) {
            return VehicleResolution.Failed(EngineFailure.Malformed("The engine accepted the vehicle but did not say its id."))
        }
        cursors.setEngineVehicleId(vehicle.obdMac, id)
        remember(listing + EngineVehicle(id, guid, mac, deleted = false))
        log("car data upload: created an engine vehicle for ${vehicle.name}")
        return VehicleResolution.Resolved(id, created = true)
    }

    private sealed interface Fetched {
        data class Rows(val rows: List<EngineVehicle>) : Fetched
        data object Incomplete : Fetched
        data class Error(val failure: EngineFailure) : Fetched
    }

    /** Every vehicle on the engine, tombstones included (they are needed to refuse resurrecting
     * a deleted guid). Mirrors the phone's page loop: `since` is the keyset cursor, `after` its
     * tiebreak. A walk that does not reach `next: null` is [Fetched.Incomplete], never a partial
     * list matched against. */
    private suspend fun fetchVehicles(): Fetched {
        val rows = LinkedHashMap<String, EngineVehicle>()
        var since: String? = null
        var after: String? = null
        repeat(MAX_PAGES) {
            val query = buildMap {
                since?.let { put("since", it) }
                after?.let { put("after", it) }
            }
            val result = http.get("/api/fleet/vehicles/", query)
            val ok = result.getOrNull() ?: return Fetched.Error(failureOf(result.exceptionOrNull()))
            val page = runCatching { Json.parseToJsonElement(ok.body).jsonObject }.getOrNull()
                ?: return Fetched.Error(EngineFailure.Malformed("The engine's vehicle list was not readable."))
            val results = (page["results"] as? JsonArray) ?: return Fetched.Error(
                EngineFailure.Malformed("The engine's vehicle list had no results."),
            )
            for (element in results) {
                val o = element.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
                rows[id] = EngineVehicle(
                    id = id,
                    originGuid = o["origin_guid"]?.jsonPrimitive?.contentOrNull,
                    lastObdMac = o["last_obd_mac"]?.jsonPrimitive?.contentOrNull,
                    deleted = o["deleted_at"]?.jsonPrimitive?.contentOrNull != null,
                )
            }
            val next = page["next"]?.jsonPrimitive?.contentOrNull ?: return Fetched.Rows(rows.values.toList())
            val nextAfter = page["next_after"]?.jsonPrimitive?.contentOrNull
            if (next == since && nextAfter == after) return Fetched.Incomplete
            since = next
            after = nextAfter
        }
        return Fetched.Incomplete
    }

    // --- sending -------------------------------------------------------------------------------

    private class CarResult(
        val samples: Int,
        val codeEvents: Int,
        val drives: Int,
        val moreWaiting: Boolean,
        val failure: String?,
    )

    private suspend fun sendCar(vehicle: Vehicle, engineId: String): CarResult {
        val key = vehicle.obdMac
        var samples = 0
        var more = false
        var batches = 0
        while (true) {
            val after = cursors.sampleCursor(key)
            val rows = source.samplesAfter(key, after, OBD_BATCH_MAX)
            if (rows.isEmpty()) break
            if (batches >= maxBatches) {
                more = true
                break
            }
            val wire = rows.mapNotNull { FleetUploadWire.obdSample(engineId, it) }
            val skipped = rows.size - wire.size
            if (skipped > 0) log("car data upload: left out $skipped sample(s) the engine would refuse (blank pid or non-finite value)")
            if (wire.isNotEmpty()) {
                val result = http.post(OBD_BATCH_PATH, FleetUploadWire.batchBody(wire))
                if (result.isFailure) {
                    val failure = failureOf(result.exceptionOrNull())
                    // A 4xx that names the vehicle means the cached mapping is stale; re-resolve next run.
                    if (failure is EngineFailure.Refused && failure.status in 400..499) cursors.clearEngineVehicleId(key)
                    return CarResult(samples, 0, 0, more, failure.sentence)
                }
            }
            // Only here, after the 2xx (or a batch that was entirely unsendable), does the cursor move.
            cursors.setSampleCursor(key, rows.last().id)
            samples += wire.size
            batches++
            if (rows.size < OBD_BATCH_MAX) break
        }
        var codes = 0
        val events = source.codeEventsAfter(key, cursors.codeEventCursor(key), CODE_EVENT_LIMIT)
        if (events.size >= CODE_EVENT_LIMIT) more = true
        for (event in events) {
            val body = FleetUploadWire.codeEvent(engineId, event)
            if (body == null) {
                log("car data upload: skipped a code event whose codes were not a JSON array")
            } else {
                val path = "/api/fleet/code_events/${FleetUploadWire.codeEventSyncId(event).pathSafe()}/"
                val result = http.put(path, body)
                if (result.isFailure) {
                    val failure = failureOf(result.exceptionOrNull())
                    return CarResult(samples, codes, 0, more, failure.sentence)
                }
                codes++
            }
            cursors.setCodeEventCursor(key, event.id)
        }
        var drivesSent = 0
        val drives = source.drivesAfter(key, cursors.driveCursor(key), DRIVE_LIMIT)
        if (drives.size >= DRIVE_LIMIT) more = true
        for (drive in drives) {
            val body = FleetUploadWire.drive(engineId, drive)
            if (body == null) {
                log("car data upload: skipped a drive the engine would refuse (bad miles or end before start)")
            } else {
                val result = http.put("/api/fleet/drives/${drive.syncId.pathSafe()}/", body)
                if (result.isFailure) {
                    return CarResult(samples, codes, drivesSent, more, failureOf(result.exceptionOrNull()).sentence)
                }
                drivesSent++
            }
            cursors.setDriveCursor(key, drive.id)
        }
        return CarResult(samples, codes, drivesSent, more, null)
    }

    private fun String.pathSafe(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    private fun failureOf(t: Throwable?): EngineFailure =
        (t as? EngineHttpException)?.failure
            ?: EngineFailure.Malformed("The engine did not answer in a way this unit understood.")

    companion object {
        /** `OBD_BATCH_MAX` in `server/api/fleet.py`: the engine refuses a larger batch whole. Keep
         * in step with it; a bigger number here would 400 every upload, not truncate. */
        const val OBD_BATCH_MAX = 1000
        const val OBD_BATCH_PATH = "/api/fleet/obd_samples/batch/"

        /** One run's cap per car, so a first sync of a year of history does not hold the lock for
         * minutes; the rest goes on later runs (`Sent.moreWaiting`). */
        const val MAX_BATCHES_PER_RUN = 40
        private const val CODE_EVENT_LIMIT = 500
        private const val DRIVE_LIMIT = 500
        private const val MAX_PAGES = 50
        private const val MAX_REASON_CHARS = 200
        private val lock = Mutex()

        /** The id the phone gives an engine vehicle's `origin_guid`
         * (`FleetRecordBridge.vehicleGuid`): same bytes, same UUID. */
        fun vehicleGuid(localVehicleId: String): String =
            UUID.nameUUIDFromBytes("fleet-vehicle:$localVehicleId".toByteArray()).toString()

        /** A real dongle MAC, not a synthetic `car:<uuid>` profile id or `default`. */
        fun isDongleMac(id: String): Boolean = MAC.matches(id)

        private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    }
}
