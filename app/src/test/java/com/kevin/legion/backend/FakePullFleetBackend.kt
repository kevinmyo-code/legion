package com.kevin.legion.backend

/**
 * A [FleetBackend] for [ObdSamplePullTest] and [ObdSampleNoReuploadTest]: serves `obd_samples`
 * pages the way the engine does (oldest first, `recorded_at` INCLUSIVE, `next` = the last row's
 * instant while more remain), records every request and every upload, and can be told to fail
 * after N pages. Everything else is out of scope and throws.
 */
internal class FakePullFleetBackend(private val pageSize: Int = 5) : FleetBackend {
    val samples = mutableMapOf<String, MutableList<RemoteObdSample>>() // by vehicleServerId
    val vehicles = mutableMapOf<String, RemoteVehicle>() // by originGuid, for the upload half
    val requestedSince = mutableListOf<Long>()
    val uploaded = mutableListOf<ObdSampleUpload>()

    /** Pages served before every further request fails; null never fails. */
    var failAfterPages: Int? = null
    private var served = 0

    fun resetServed() {
        served = 0
    }

    override suspend fun fetchObdSamplesPage(vehicleServerId: String, sinceMs: Long): Result<RemoteObdSamplePage> {
        requestedSince.add(sinceMs)
        if (failAfterPages != null && served >= failAfterPages!!) {
            return Result.failure(FleetBackendException("simulated: the engine is unreachable"))
        }
        served++
        val matching = (samples[vehicleServerId] ?: mutableListOf())
            .filter { it.recordedAtMs >= sinceMs }
            .sortedBy { it.recordedAtMs }
        val page = matching.take(pageSize)
        val next = if (matching.size > pageSize) page.last().recordedAtMs else null
        afterFirstRecordedPage?.let {
            afterFirstRecordedPage = null
            it()
        }
        return Result.success(RemoteObdSamplePage(page, next))
    }

    /** A sample as the engine holds it: when it was STORED and under which row id. */
    class Arrived(val sample: RemoteObdSample, val createdAtMs: Long, val id: String)

    val arrivals = mutableMapOf<String, MutableList<Arrived>>() // by vehicleServerId
    val requestedArrival = mutableListOf<Pair<String, String?>>()
    var arrivalSupported = false
    override val supportsArrivalFeed: Boolean get() = arrivalSupported

    /** Runs once, right after the first `recorded_at` page is served: lets a test make a row
     *  arrive DURING the backfill. */
    var afterFirstRecordedPage: (() -> Unit)? = null

    /** Stores a sample in both feeds, the way the engine's one table serves both. */
    fun store(vehicleServerId: String, sample: RemoteObdSample, createdAtMs: Long, id: String) {
        samples.getOrPut(vehicleServerId) { mutableListOf() }.add(sample)
        arrivals.getOrPut(vehicleServerId) { mutableListOf() }.add(Arrived(sample, createdAtMs, id))
    }

    override suspend fun fetchObdSamplesArrivalPage(
        vehicleServerId: String,
        receivedAfter: String,
        afterId: String?,
    ): Result<RemoteObdArrivalPage> {
        requestedArrival.add(receivedAfter to afterId)
        if (failAfterPages != null && served >= failAfterPages!!) {
            return Result.failure(FleetBackendException("simulated: the engine is unreachable"))
        }
        served++
        val from = java.time.Instant.parse(receivedAfter).toEpochMilli()
        val matching = (arrivals[vehicleServerId] ?: mutableListOf())
            .filter {
                if (afterId == null) {
                    it.createdAtMs >= from
                } else {
                    it.createdAtMs > from || (it.createdAtMs == from && it.id > afterId)
                }
            }
            .sortedWith(compareBy({ it.createdAtMs }, { it.id }))
        val page = matching.take(pageSize)
        val hasMore = matching.size > pageSize
        val last = page.lastOrNull()
        return Result.success(
            RemoteObdArrivalPage(
                samples = page.map { it.sample },
                hasMore = hasMore,
                cursorReceivedAfter = last?.let { java.time.Instant.ofEpochMilli(it.createdAtMs).toString() },
                cursorAfterId = if (hasMore) last?.id else null,
            ),
        )
    }

    override suspend fun fetchActiveVehicles(): Result<List<RemoteVehicle>> = Result.success(vehicles.values.toList())
    override suspend fun uploadObdSampleBatch(batch: List<ObdSampleUpload>): Result<Unit> {
        uploaded.addAll(batch)
        return Result.success(Unit)
    }
    override suspend fun countObdSamples(): Result<Long> = Result.success(0L)

    override suspend fun uploadMigratedVehicle(vehicle: MigratedVehicle): Result<Boolean> =
        error("out of scope")
    override suspend fun upsertVehicle(vehicle: VehicleUpload): Result<RemoteVehicle> =
        error("out of scope")
    override suspend fun fetchActiveServiceHistory(): Result<List<RemoteServiceHistory>> =
        error("out of scope")
    override suspend fun uploadMigratedServiceHistory(history: MigratedServiceHistory): Result<Boolean> =
        error("out of scope")
    override suspend fun upsertServiceHistory(history: ServiceHistoryUpload): Result<RemoteServiceHistory> =
        error("out of scope")
    override suspend fun fetchActiveDrives(): Result<List<RemoteDrive>> = error("out of scope")
    override suspend fun upsertDrive(drive: DriveUpload): Result<RemoteDrive> = error("out of scope")
    override suspend fun fetchActiveCodeEvents(): Result<List<RemoteCodeEvent>> = error("out of scope")
    override suspend fun upsertCodeEvent(event: CodeEventUpload): Result<RemoteCodeEvent> =
        error("out of scope")
    override suspend fun fetchActiveCodeClearEvents(): Result<List<RemoteCodeClearEvent>> =
        error("out of scope")
    override suspend fun upsertCodeClearEvent(event: CodeClearEventUpload): Result<RemoteCodeClearEvent> =
        error("out of scope")
    override suspend fun fetchActiveOilAnalyses(): Result<List<RemoteOilAnalysis>> = error("out of scope")
    override suspend fun upsertOilAnalysis(analysis: OilAnalysisUpload): Result<RemoteOilAnalysis> =
        error("out of scope")
    override suspend fun fetchChassisQuirks(): Result<List<RemoteChassisQuirk>> = error("out of scope")
    override suspend fun upsertChassisQuirk(quirk: ChassisQuirkUpload): Result<RemoteChassisQuirk> =
        error("out of scope")
    override suspend fun fetchVehicleSpecs(): Result<List<RemoteVehicleSpec>> = error("out of scope")
    override suspend fun upsertVehicleSpec(spec: VehicleSpecUpload): Result<RemoteVehicleSpec> =
        error("out of scope")
    override suspend fun fetchActiveBuildEntries(): Result<List<RemoteBuildEntry>> = error("out of scope")
    override suspend fun upsertBuildEntry(entry: BuildEntryUpload): Result<RemoteBuildEntry> =
        error("out of scope")
    override suspend fun fetchActiveDriveReassignments(): Result<List<RemoteDriveReassignment>> =
        error("out of scope")
    override suspend fun upsertDriveReassignment(
        reassignment: DriveReassignmentUpload,
    ): Result<RemoteDriveReassignment> =
        error("out of scope")
    override suspend fun fetchActiveMaintenanceSchedules(): Result<List<RemoteMaintenanceSchedule>> =
        error("out of scope")
    override suspend fun upsertMaintenanceSchedule(
        schedule: MaintenanceScheduleUpload,
    ): Result<RemoteMaintenanceSchedule> =
        error("out of scope")
}
