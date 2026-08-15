package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.location.LocationController
import com.kevin.legion.service.ConversationState
import kotlinx.coroutines.delay

/**
 * The compounding-history pipeline: samples a fixed PID set into the
 * obd_samples table every 30 seconds while the engine runs. This time series
 * is the moat — trend tools, foresight notes, and Nightrunner Wrapped all
 * read from it. ~18 MB/year at this cadence; rows older than a year purge.
 *
 * Behavior notes:
 *  - Only samples while the engine runs (rpm > 0) — a parked car with the
 *    adapter powered contributes nothing but battery drain.
 *  - Skips ticks during a live conversation ([ConversationState.isBusy]) so
 *    PID reads never contend with voice on the mutex-guarded port.
 *  - A PID that fails [MAX_CONSECUTIVE_FAILS] reads in a row is dropped for
 *    the rest of the process — cheap supported-PID discovery for older ECUs.
 *  - MPG: fuel burn integrates MAF (grams/s / AFR / grams-per-gallon, gasoline
 *    assumed). Distance prefers the GPS trip-mile accumulator on the Vehicle row,
 *    and falls back to integrating OBD speed (PID 010D) when GPS produces nothing
 *    (2026-07-16: a head unit with no GPS antenna wired reports no location at
 *    all, which left driveMiles at 0, made finalizeDrive early-return, and wrote
 *    no TRIP_MILES/MPG_TRIP - so every recap read "0 drives, 0 miles" on a real
 *    drive). A finished drive (>1 mi, >0.05 gal) writes one MPG_TRIP summary
 *    sample.
 *  - A cold engine-on (coolant below [COLD_START_C]) triggers a 60-second
 *    burst at 10s cadence first — warm-up behavior (trims, idle, warm rate)
 *    is where O2 sensors, vacuum leaks, and cat aging show earliest.
 */
object TelemetryRecorder {
    private const val TAG = "TelemetryRecorder"

    const val TICK_MS = 30_000L
    /**
     * How long the DEVICE keeps telemetry. The driver's Drive keeps everything as a
     * permanent archive (Kevin, 2026-07-16), so this bounds the head unit's eMMC,
     * not the moat. Read by SyncEngine.monthsToSync, which must not pull back a
     * month this has purged - that is what silently undid the purge on every sync.
     */
    const val RETENTION_MS = 365L * 24 * 60 * 60 * 1000
    // Tombstone GC (B19): a soft-deleted car_tasks row is kept this long so a
    // slower-syncing device still sees the deletion before it's purged for good.
    private const val TOMBSTONE_HORIZON_MS = 90L * 24 * 60 * 60 * 1000
    private const val AFR_GASOLINE = 14.7
    private const val GRAMS_PER_GALLON = 2801.0   // gasoline, 0.74 kg/L
    // OBD speed (PID 010D) reports km/h; driveMiles is miles.
    private const val KM_PER_MILE = 1.609344
    private const val COLD_START_C = 40
    private const val MAX_CONSECUTIVE_FAILS = 3
    private const val MAX_DT_SEC = 90.0           // longer gap = we weren't driving
    private const val MIN_TRIP_MILES = 1.0
    private const val MIN_TRIP_GALLONS = 0.05
    // Per-tick GPS distance sanity bounds (moved from VehicleController when its
    // separate GPS-only trackTripMileage loop was consolidated into this one,
    // 2026-07-19): below the floor is GPS jitter while parked, above the ceiling
    // is a teleport (cold fix landing after a long blackout), neither is driving.
    private const val METERS_PER_MILE = 1609.34
    private const val MIN_TICK_MILES = 0.001
    private const val MAX_TICK_MILES = 5.0
    private const val PREFS = "telemetry"

    // Current-drive accumulators (process state; a drive never spans a restart
    // that matters — the engine-off finalizer runs before the head unit sleeps).
    /**
     * True while the engine is turning, published from the one loop that already
     * knows (it polls RPM every tick anyway). Read by AriaForegroundService's
     * periodic sync so a parked car doesn't sync all day on the driver's hotspot.
     * Best-effort: it lags reality by at most one TICK_MS, which is fine for
     * deciding whether to push a snapshot.
     */
    @Volatile var isEngineRunning = false
        private set

    @Volatile private var driveMiles = 0.0
    @Volatile private var driveGallons = 0.0

    /** MPG of the drive in progress, or null until it has accumulated a real mile. */
    fun currentDriveMpg(): Double? =
        if (driveMiles > MIN_TRIP_MILES && driveGallons > MIN_TRIP_GALLONS) driveMiles / driveGallons else null

    /** Lifetime MPG across all finished drives since install, or null if none yet. */
    fun lifetimeMpg(context: Context): Double? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val vehicleId = ActiveVehicle.current(context)
        val gal = p.getFloat("${vehicleId}_gal", 0f).toDouble()
        val mi = p.getFloat("${vehicleId}_mi", 0f).toDouble()
        return if (gal > MIN_TRIP_GALLONS && mi > MIN_TRIP_MILES) mi / gal else null
    }

    /** Infinite sampling loop; launch once from the foreground service. */
    suspend fun run(context: Context) {
        val db = CarDatabase.getDatabase(context)
        runCatching { db.odbSampleDao().purgeOlderThan(System.currentTimeMillis() - RETENTION_MS) }
        runCatching { db.carTaskDao().purgeTombstones(System.currentTimeMillis() - TOMBSTONE_HORIZON_MS) }

        val failCounts = mutableMapOf<String, Int>()
        var engineWasOn = false
        var offTicks = 0
        var lastTickAt = 0L
        var lastLocation: android.location.Location? = null

        while (true) {
            delay(TICK_MS)
            if (!ObdBluetoothManager.isConnected || ConversationState.isBusy) continue

            val rpm = ObdBluetoothManager.getRpm()
            val engineOn = rpm != null && rpm > 0

            if (!engineOn) {
                if (engineWasOn) {
                    offTicks++
                    if (offTicks >= 2) {
                        finalizeDrive(context)
                        engineWasOn = false
                        isEngineRunning = false
                        offTicks = 0
                        lastTickAt = 0L
                        lastLocation = null
                    }
                }
                continue
            }
            offTicks = 0

            if (!engineWasOn) {
                engineWasOn = true
                isEngineRunning = true
                val coolant = ObdBluetoothManager.getCoolantTemp()
                if (coolant != null && coolant < COLD_START_C) {
                    coldStartBurst(context, coolant)
                }
            }

            val now = System.currentTimeMillis()
            val loc = LocationController.state.value
            val samples = mutableListOf<OdbSample>()
            fun add(pid: String, value: Double?, unit: String) {
                if (value == null) {
                    failCounts[pid] = (failCounts[pid] ?: 0) + 1
                    return
                }
                failCounts[pid] = 0
                samples.add(
                    OdbSample(
                        vehicleId = vehicleId(context),
                        pid = pid, value = value, unit = unit,
                        timestamp = now, lat = loc?.latitude, lng = loc?.longitude,
                    )
                )
            }
            fun wanted(pid: String) = (failCounts[pid] ?: 0) < MAX_CONSECUTIVE_FAILS

            add("010C", rpm!!.toDouble(), "rpm")
            if (wanted("0105")) add("0105", ObdBluetoothManager.getCoolantTemp()?.toDouble(), "°C")
            if (wanted("0104")) add("0104", ObdBluetoothManager.getEngineLoad(), "%")
            var maf: Double? = null
            if (wanted("0110")) { maf = ObdBluetoothManager.getMaf(); add("0110", maf, "g/s") }
            var speedKmh: Double? = null
            if (wanted("010D")) {
                speedKmh = ObdBluetoothManager.getSpeedKmh()?.toDouble()
                add("010D", speedKmh, "km/h")
            }
            if (wanted("012F")) add("012F", ObdBluetoothManager.getFuelLevel(), "%")
            if (wanted("0106")) add("0106", ObdBluetoothManager.getShortFuelTrim(), "%")
            if (wanted("0107")) add("0107", ObdBluetoothManager.getLongFuelTrim(), "%")
            if (wanted("ATRV")) add("ATRV", ObdBluetoothManager.getBatteryVoltage(), "V")

            runCatching { samples.forEach { db.odbSampleDao().insert(it) } }
                .onFailure { Log.w(TAG, "sample insert failed: ${it.message}") }

            // --- MPG accumulation -------------------------------------------
            if (lastTickAt != 0L && maf != null) {
                val dtSec = ((now - lastTickAt) / 1000.0).coerceAtMost(MAX_DT_SEC)
                driveGallons += maf * dtSec / (AFR_GASOLINE * GRAMS_PER_GALLON)
            }
            // --- Distance: GPS first, OBD speed as the fallback -------------
            // GPS is preferred when present (it measures ground truth), but a head
            // unit with no GPS antenna wired produces NO location at all - and the
            // whole recap/MPG chain hangs off this number. Kevin's XJ (2026-07-16)
            // has no fix; integrating PID 010D closes that with no GPS at all.
            //
            // (2026-07-19) This loop is now also the SINGLE odometer writer:
            // VehicleController.trackTripMileage used to be a separate GPS-only
            // service loop that wrote tripMilesSinceBaseline while this one read
            // the deltas back out - two loops, one dead on a no-GPS car, and the
            // persistent odometer never moved without a fix. The per-tick miles
            // computed here (GPS-or-OBD) now feed BOTH the drive accumulator and
            // the persisted odometer estimate, and the old loop is deleted.
            //
            // Same 30s granularity the MAF fuel integration above already accepts.
            // It undercounts stop-and-go slightly (a tick samples an instant, not
            // an average), the honest trade for a number that exists at all.
            val dtSecDist = if (lastTickAt != 0L) ((now - lastTickAt) / 1000.0).coerceAtMost(MAX_DT_SEC) else 0.0
            var tickMiles = 0.0
            val prevLoc = lastLocation
            if (loc != null) lastLocation = loc
            if (loc != null && prevLoc != null) {
                val out = FloatArray(1)
                android.location.Location.distanceBetween(
                    prevLoc.latitude, prevLoc.longitude, loc.latitude, loc.longitude, out,
                )
                val gpsMiles = out[0] / METERS_PER_MILE
                if (gpsMiles in MIN_TICK_MILES..MAX_TICK_MILES) tickMiles = gpsMiles
            }
            if (tickMiles == 0.0 && speedKmh != null && speedKmh > 0.0 && dtSecDist > 0.0) {
                tickMiles = speedKmh * dtSecDist / 3600.0 / KM_PER_MILE
            }
            if (tickMiles > 0.0) {
                driveMiles += tickMiles
                // Targeted write (ticket 13,
                // .scratch/fleet-maintenance/issues/13-the-jeep-row-lost-its-identity.md):
                // accumulates tripMilesSinceBaseline IN SQL rather than reading a
                // Vehicle here and upserting the whole row back - this loop is the
                // single highest-frequency writer of the vehicles table (every 30s
                // while driving) and a whole-row upsert of a possibly-stale read was
                // exactly the shape that clobbered concurrent identity/odometer
                // writes. It also no longer routes through VehicleController.currentVehicle,
                // so it can never silently seed a placeholder row for a car nobody
                // has registered yet - see VehicleDao.addTripMiles and
                // VehicleController.seedVehicle's doc for why a no-op here, for an
                // unregistered vehicle id, is the correct behavior now rather than a bug.
                runCatching {
                    db.vehicleDao().addTripMiles(vehicleId(context), tickMiles, now)
                }.onFailure { Log.w(TAG, "trip-mile update failed: ${it.message}") }
            }
            lastTickAt = now
        }
    }

    /** Writes the drive's MPG_TRIP summary + lifetime aggregates, then resets. */
    private suspend fun finalizeDrive(context: Context) {
        val miles = driveMiles
        val gallons = driveGallons
        driveMiles = 0.0
        driveGallons = 0.0
        if (miles <= MIN_TRIP_MILES || gallons <= MIN_TRIP_GALLONS) return

        runCatching {
            val dao = CarDatabase.getDatabase(context).odbSampleDao()
            val now = System.currentTimeMillis()
            val lat = LocationController.state.value?.latitude
            val lng = LocationController.state.value?.longitude
            dao.insert(
                OdbSample(
                    vehicleId = vehicleId(context),
                    pid = "MPG_TRIP", value = miles / gallons, unit = "mpg",
                    timestamp = now, lat = lat, lng = lng,
                )
            )
            // TRIP_MILES: the raw per-drive distance MPG_TRIP was computed from.
            // Previously only the ratio was kept and this was discarded - added
            // so MonthlyRecapController can sum/count/max real drives for a
            // month (miles driven, drive count, longest drive) without having
            // to reverse-engineer it from the MPG samples.
            dao.insert(
                OdbSample(
                    vehicleId = vehicleId(context),
                    pid = "TRIP_MILES", value = miles, unit = "mi",
                    timestamp = now, lat = lat, lng = lng,
                )
            )
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = vehicleId(context)
        p.edit()
            .putFloat("${id}_gal", p.getFloat("${id}_gal", 0f) + gallons.toFloat())
            .putFloat("${id}_mi", p.getFloat("${id}_mi", 0f) + miles.toFloat())
            .apply()
    }

    /**
     * 60-second cold-start burst: a COLD_START marker sample, then six reads
     * at 10s cadence of the warm-up-diagnostic PID set. Aborts if the engine
     * dies or a conversation starts (voice wins the port).
     */
    private suspend fun coldStartBurst(context: Context, coolantAtStartC: Int) {
        val db = CarDatabase.getDatabase(context)
        val id = vehicleId(context)
        runCatching {
            db.odbSampleDao().insert(
                OdbSample(
                    vehicleId = id, pid = "COLD_START",
                    value = coolantAtStartC.toDouble(), unit = "°C",
                    timestamp = System.currentTimeMillis(),
                    lat = LocationController.state.value?.latitude,
                    lng = LocationController.state.value?.longitude,
                )
            )
        }
        repeat(6) {
            delay(10_000)
            if (!ObdBluetoothManager.isConnected || ConversationState.isBusy) return
            val rpm = ObdBluetoothManager.getRpm() ?: return
            if (rpm <= 0) return
            val now = System.currentTimeMillis()
            val loc = LocationController.state.value
            val burst = listOfNotNull(
                OdbSample(vehicleId = id, pid = "010C", value = rpm.toDouble(), unit = "rpm", timestamp = now, lat = loc?.latitude, lng = loc?.longitude),
                ObdBluetoothManager.getCoolantTemp()?.let { OdbSample(vehicleId = id, pid = "0105", value = it.toDouble(), unit = "°C", timestamp = now, lat = loc?.latitude, lng = loc?.longitude) },
                ObdBluetoothManager.getShortFuelTrim()?.let { OdbSample(vehicleId = id, pid = "0106", value = it, unit = "%", timestamp = now, lat = loc?.latitude, lng = loc?.longitude) },
                ObdBluetoothManager.getLongFuelTrim()?.let { OdbSample(vehicleId = id, pid = "0107", value = it, unit = "%", timestamp = now, lat = loc?.latitude, lng = loc?.longitude) },
                ObdBluetoothManager.getIntakeAirTemp()?.let { OdbSample(vehicleId = id, pid = "010F", value = it.toDouble(), unit = "°C", timestamp = now, lat = loc?.latitude, lng = loc?.longitude) },
            )
            runCatching { burst.forEach { db.odbSampleDao().insert(it) } }
        }
    }

    // Car profiles (2026-07-16): the driver's picked car, falling back to the
    // connected dongle. NOT the dongle MAC directly any more - one dongle moved
    // between two cars used to make them the same vehicle. See ActiveVehicle.
    private fun vehicleId(context: Context): String = ActiveVehicle.current(context)
}
