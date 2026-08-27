package com.kevin.legion.backend

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val VEHICLES_TABLE = "vehicles"
private const val SERVICE_HISTORY_TABLE = "service_history"
private const val DRIVES_TABLE = "drives"

private fun tsOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }
private fun parseTs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()
private fun parseTsOrNull(s: String?): Long? = s?.let { parseTs(it) }
// service_history.service_date is a `date`, not a `timestamptz` - same UTC-midnight convention
// EventUpsertDto's repeat_end_date uses, reused verbatim rather than reinvented.
private fun dateOrNull(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
private fun parseDateOrNull(s: String?): Long? = s?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }

/**
 * The wire shape for [SupabaseFleetBackend.uploadMigratedVehicle]. Every nullable property is
 * deliberately required (no `= null` default) - same trick [PlaceUpsertDto.deletedAt]'s own doc
 * comment explains at length: `encodeDefaults = false` drops a property equal to its declared
 * default, and `null` is still a default, so an un-set nullable would silently vanish from the
 * outgoing JSON. This is a one-shot INSERT, not a partial PATCH, so every column must be present.
 */
@Serializable
private data class VehicleInsertDto(
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val trim: String?,
    val engine: String?,
    val confirmed: Boolean,
    @SerialName("odometer_baseline") val odometerBaseline: Int?,
    @SerialName("odometer_baseline_at") val odometerBaselineAt: String?,
    @SerialName("origin_guid") val originGuid: String,
)

/** The wire shape read back off `public.vehicles` for every operation. */
@Serializable
private data class VehicleRowDto(
    val id: String,
    val name: String,
    val make: String,
    val model: String,
    val year: Int,
    val trim: String? = null,
    val engine: String? = null,
    val confirmed: Boolean = false,
    @SerialName("odometer_baseline") val odometerBaseline: Int? = null,
    @SerialName("odometer_baseline_at") val odometerBaselineAt: String? = null,
    @SerialName("origin_guid") val originGuid: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteVehicle() = RemoteVehicle(
        serverId = id,
        name = name,
        make = make,
        model = model,
        year = year,
        trim = trim,
        engine = engine,
        confirmed = confirmed,
        odometerBaseline = odometerBaseline,
        odometerBaselineAtMs = parseTsOrNull(odometerBaselineAt),
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

/** The wire shape for [SupabaseFleetBackend.uploadMigratedServiceHistory] - same "every column
 * required, no defaults" posture as [VehicleInsertDto]. */
@Serializable
private data class ServiceHistoryInsertDto(
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    val mileage: Int?,
    @SerialName("service_date") val serviceDate: String?,
    @SerialName("cost_cents") val costCents: Long?,
    val kind: String,
    @SerialName("origin_guid") val originGuid: String,
)

/** The wire shape read back off `public.service_history` for every operation. */
@Serializable
private data class ServiceHistoryRowDto(
    val id: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("service_name") val serviceName: String,
    val mileage: Int? = null,
    @SerialName("service_date") val serviceDate: String? = null,
    @SerialName("cost_cents") val costCents: Long? = null,
    val kind: String,
    @SerialName("origin_guid") val originGuid: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteServiceHistory() = RemoteServiceHistory(
        serverId = id,
        vehicleServerId = vehicleId,
        serviceName = serviceName,
        mileage = mileage,
        serviceDateEpochMs = parseDateOrNull(serviceDate),
        costCents = costCents,
        kind = kind,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
        originGuid = originGuid,
    )
}

/**
 * The wire shape for [SupabaseFleetBackend.upsertDrive]. Unlike [VehicleInsertDto]/
 * [ServiceHistoryInsertDto], there is no `origin_guid` here at all - see [DriveUpload]'s own class
 * doc for why drives key on `sync_id` instead.
 */
@Serializable
private data class DriveUpsertDto(
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    val miles: Double,
    // Deliberately required, no `= null` default - a genuinely fuel-unmeasured drive must send an
    // explicit JSON null, not omit the key, or a later upsert-with-a-real-reading could never tell
    // "still unmeasured" apart from "this write forgot to mention gallons at all". Postgres also has
    // no existing value to preserve here the way an UPDATE would, since this table has no live edit
    // path - every upsert is either a fresh insert or a harmless re-post of identical data.
    val gallons: Double?,
    @SerialName("end_reason") val endReason: String,
)

/** The wire shape read back off `public.drives` for every operation. */
@Serializable
private data class DriveRowDto(
    val id: String,
    @SerialName("sync_id") val syncId: String,
    @SerialName("vehicle_id") val vehicleId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    val miles: Double,
    val gallons: Double? = null,
    @SerialName("end_reason") val endReason: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
) {
    fun toRemoteDrive() = RemoteDrive(
        serverId = id,
        syncId = syncId,
        vehicleServerId = vehicleId,
        startedAtMs = parseTs(startedAt),
        endedAtMs = parseTs(endedAt),
        miles = miles,
        gallons = gallons,
        endReason = endReason,
        updatedAtMs = parseTs(updatedAt),
        deleted = deletedAt != null,
    )
}

/**
 * [FleetBackend]'s real implementation over Postgrest, against `public.vehicles`,
 * `public.service_history` and `public.drives` (`supabase/migrations/20260825000500_aspect_places_fleet.sql`,
 * `supabase/migrations/20260826000200_fleet_drives.sql`). This is the deliberately untested seam in
 * the fleet cutover, same posture as [SupabasePlacesBackend]/[SupabaseEventsBackend] - exercising it
 * for real needs a live project. [FleetBackend] is the fake-friendly interface; every branch here
 * does nothing but translate exceptions and decode DTOs.
 */
class SupabaseFleetBackend(private val client: SupabaseClient) : FleetBackend {

    private suspend inline fun <T> translating(action: String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        Result.failure(FleetBackendException("Supabase rejected the request to $action: ${e.error}"))
    } catch (e: IOException) {
        Result.failure(FleetBackendException("Couldn't reach the server to $action."))
    } catch (e: Exception) {
        Result.failure(FleetBackendException("Couldn't $action: ${e.message ?: "unknown error"}"))
    }

    override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> = translating("load your vehicles") {
        client.postgrest.from(VEHICLES_TABLE)
            .select {
                filter { filter("deleted_at", FilterOperator.IS, "null") }
            }
            .decodeList<VehicleRowDto>()
            .map { it.toRemoteVehicle() }
    }

    /** Same "select by origin_guid first" shape as [SupabaseEventsBackend.uploadMigratedEvent] -
     * this is a re-run guard, not a gate-immutability workaround, and `Result.success(false)` on an
     * existing row means exactly "already migrated", never a failure. */
    override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> =
        translating("upload a migrated vehicle") {
            val existing = client.postgrest.from(VEHICLES_TABLE)
                .select {
                    filter { eq("origin_guid", vehicle.originGuid) }
                }
                .decodeList<VehicleRowDto>()
            if (existing.isNotEmpty()) return@translating false

            client.postgrest.from(VEHICLES_TABLE).insert(
                VehicleInsertDto(
                    name = vehicle.name,
                    make = vehicle.make,
                    model = vehicle.model,
                    year = vehicle.year,
                    trim = vehicle.trim,
                    engine = vehicle.engine,
                    confirmed = vehicle.confirmed,
                    odometerBaseline = vehicle.odometerBaseline,
                    odometerBaselineAt = tsOrNull(vehicle.odometerBaselineAtMs),
                    originGuid = vehicle.originGuid,
                ),
            )
            true
        }

    override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> =
        translating("load your service history") {
            client.postgrest.from(SERVICE_HISTORY_TABLE)
                .select {
                    filter { filter("deleted_at", FilterOperator.IS, "null") }
                }
                .decodeList<ServiceHistoryRowDto>()
                .map { it.toRemoteServiceHistory() }
        }

    override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> =
        translating("upload a migrated service record") {
            val existing = client.postgrest.from(SERVICE_HISTORY_TABLE)
                .select {
                    filter { eq("origin_guid", history.originGuid) }
                }
                .decodeList<ServiceHistoryRowDto>()
            if (existing.isNotEmpty()) return@translating false

            client.postgrest.from(SERVICE_HISTORY_TABLE).insert(
                ServiceHistoryInsertDto(
                    vehicleId = history.vehicleServerId,
                    serviceName = history.serviceName,
                    mileage = history.mileage,
                    serviceDate = dateOrNull(history.serviceDateEpochMs),
                    costCents = history.costCents,
                    kind = history.kind,
                    originGuid = history.originGuid,
                ),
            )
            true
        }

    override suspend fun fetchActiveDrives(): Result<List<RemoteDrive>> = translating("load your drives") {
        client.postgrest.from(DRIVES_TABLE)
            .select {
                filter { filter("deleted_at", FilterOperator.IS, "null") }
            }
            .decodeList<DriveRowDto>()
            .map { it.toRemoteDrive() }
    }

    override suspend fun upsertDrive(drive: DriveUpload): Result<RemoteDrive> = translating("save that drive") {
        client.postgrest.from(DRIVES_TABLE)
            .upsert(
                DriveUpsertDto(
                    syncId = drive.syncId,
                    vehicleId = drive.vehicleServerId,
                    startedAt = Instant.ofEpochMilli(drive.startedAtMs).toString(),
                    endedAt = Instant.ofEpochMilli(drive.endedAtMs).toString(),
                    miles = drive.miles,
                    gallons = drive.gallons,
                    endReason = drive.endReason,
                ),
            ) {
                onConflict = "sync_id"
                select()
            }
            .decodeSingle<DriveRowDto>()
            .toRemoteDrive()
    }
}
