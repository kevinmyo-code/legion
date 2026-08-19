package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Drive
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
 *    PID reads never contend with voice on the mutex-guarded port. **This
 *    check touches NO drive state** - see the link-loss note directly below
 *    for why that used to not be true.
 *  - **A lost OBD link (`!ObdBluetoothManager.isConnected`) is NOT the same event as a busy voice
 *    turn, and used to be treated as one** (`.scratch/drive-ui/issues/05-trip-content.md`/
 *    `09-mpg-scale-bug.md`'s "bigger finding"). The old guard was a single
 *    `if (!ObdBluetoothManager.isConnected || ConversationState.isBusy) continue` - because the
 *    engine-off finalizer sat BELOW that line, a dropped link made every following tick `continue`
 *    forever: `engineWasOn` stayed `true`, `driveMiles`/`driveGallons` kept accumulating, and the
 *    next reconnect silently resumed the SAME drive. Measured on Kevin's own database: one
 *    "finalised drive" spanning 610 minutes around a single 9-hour gap - two sessions merged, with
 *    `MAX_DT_SEC` the only reason it wasn't worse (it clamps a stale gap's distance/fuel
 *    contribution, it does not split the drive). [tickGuardFor] now separates the two: busy alone
 *    is [TickGuard.SKIP_BUSY] (unchanged skip, no drive state touched), a lost link is
 *    [TickGuard.SKIP_LINK_LOST] and, once it has persisted [LINK_LOST_TICKS] ticks in a row while a
 *    drive was in progress, ends that drive with [DriveEndReason.LINK_LOST] via [finalizeDrive] -
 *    same reset shape the engine-off path already used ([linkLostShouldFinalize]'s own doc has the
 *    threshold reasoning).
 *  - **[isEngineRunning] now flips `false` on a link-loss finalisation too, which it never did
 *    before this fix** (before: link loss never called [finalizeDrive] at all, so this flag stayed
 *    `true` indefinitely across the drop). [AriaForegroundService] reads it to gate periodic sync -
 *    so a genuinely parked-but-disconnected car now stops triggering syncs after [LINK_LOST_TICKS]
 *    ticks (2 minutes) instead of forever. Its LAG contract (published from the same loop that
 *    polls RPM, "at most one TICK_MS stale") is unchanged; only the link-loss case newly reaches a
 *    `false` transition at all.
 *  - A PID that fails [MAX_CONSECUTIVE_FAILS] reads in a row is dropped for
 *    the rest of the process — cheap supported-PID discovery for older ECUs.
 *    **Speed (PID 010D) is the one exception** (ticket 10,
 *    `.scratch/fleet-maintenance/issues/10-odometer-truth-and-drift.md`) - see [pidWanted]'s own doc
 *    for why a genuinely-optional-PID discovery mechanism was silently killing the odometer's own
 *    supply line.
 *  - MPG: fuel burn integrates MAF (grams/s / AFR / grams-per-gallon, gasoline
 *    assumed). **Distance prefers OBD speed (PID 010D) over GPS** (ticket 10/03 - this REVERSES
 *    what shipped before it: GPS used to go first). Ticket 03's finding is why: the dash odometer
 *    on a 1998 XJ is itself a PCM speed integration off the exact same VSS PID 010D reads (CCD
 *    message `0x84`, "PCM TO BCM | INCREMENT MILEAGE") - so preferring OBD speed is not swapping in
 *    a WORSE measurement, it puts this estimator and the manual dash reading that resets it in the
 *    same reference frame, which GPS never shares. GPS is now the fallback, used only when 010D has
 *    nothing to offer (unsupported PID, or the read failed this tick) - which is still needed: a
 *    head unit with no GPS antenna wired is not the only no-signal case, and a 010D-less car (rare,
 *    but PID support varies) must not lose distance entirely (2026-07-16: a head unit with no GPS
 *    antenna wired reports no location at all, which left driveMiles at 0, made finalizeDrive
 *    early-return, and wrote no TRIP_MILES/MPG_TRIP - so every recap read "0 drives, 0 miles" on a
 *    real drive; the fallback exists for exactly the mirror image of that gap). A finished drive
 *    (>1 mi, >0.05 gal) writes one MPG_TRIP summary sample.
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
    // Consecutive rpm-reads-as-zero-or-null ticks before an engine-off is trusted enough to
    // finalize a drive (60s @ TICK_MS) - unchanged value, named here so it can be compared
    // directly against LINK_LOST_TICKS below rather than living only as the literal `2` at the
    // call site.
    private const val ENGINE_OFF_TICKS = 2
    // Consecutive !ObdBluetoothManager.isConnected ticks before a lost link is trusted enough to
    // finalize a drive in progress (2 min @ TICK_MS) - the fix for the link-loss defect this
    // ticket closes (see the class doc). Deliberately LONGER than ENGINE_OFF_TICKS: an engine
    // reading 0 rpm for a full minute is unambiguous, but a Bluetooth link that comes back inside
    // two minutes is not evidence the drive actually ended - a brief blip (weak signal near a
    // parking structure, the adapter's own reconnect handshake) must not split one real drive into
    // two the way [MAX_DT_SEC] already protects the distance/fuel MATH inside a single drive from
    // a momentary gap. This threshold is what decides whether that drive keeps existing at all.
    private const val LINK_LOST_TICKS = 4
    // Per-tick GPS distance sanity bounds (moved from VehicleController when its
    // separate GPS-only trackTripMileage loop was consolidated into this one,
    // 2026-07-19): below the floor is GPS jitter while parked, above the ceiling
    // is a teleport (cold fix landing after a long blackout), neither is driving.
    private const val METERS_PER_MILE = 1609.34
    // Raised 0.001 -> 0.01 mi (1.61 m -> ~16.1 m), ticket 10/03: the old floor sat BELOW typical
    // 2-5 m GPS static jitter, so an idling, engine-running car accrued "phantom miles" every tick
    // - and (before this ticket's OBD-first reorder below) the GPS branch took precedence exactly
    // when 010D correctly read 0, the worst possible case. 16.1 m is more than 3x the top of that
    // jitter range - comfortably above the noise floor - while still well under a single tick's
    // real distance at any speed worth counting (a 30s tick clears it above roughly 1.2 mph). GPS
    // is the FALLBACK only now, so a slightly conservative floor here costs at most a few seconds
    // of fallback-only tracking at a genuine crawl, never real driving.
    private const val MIN_TICK_MILES = 0.01
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
    /** Epoch ms the CURRENT drive began - set when `engineWasOn` flips `true` in [run], read and
     * reset by [finalizeDrive] into [Drive.startedAt]. `0L` means no drive is in progress. */
    @Volatile private var driveStartedAt = 0L

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

    /**
     * Whether [pid] should be requested THIS tick, given [failCounts] - the general "3 fails in a
     * row -> drop for the rest of the process" discovery mechanism ([MAX_CONSECUTIVE_FAILS]), with
     * ONE exemption: **speed (010D) is always wanted** (ticket 10 fix,
     * `.scratch/fleet-maintenance/issues/10-odometer-truth-and-drift.md`).
     *
     * The general mechanism is legitimate supported-PID discovery for genuinely optional PIDs on an
     * older ECU (coolant, fuel trims, MAF...) - a car that plainly does not publish one of those has
     * no reason to be asked every 30 seconds forever. Speed is different: virtually every OBD-II car
     * supports it, distance accrual is now the ODOMETER'S OWN preferred source (010D over GPS - see
     * this object's class doc), and a real "this car doesn't support 010D" is not the failure mode
     * that actually shows up. **Three transient misses in a row** (a busy port, a momentary
     * Bluetooth hiccup - see `.scratch/android-auto/issues/13`'s quiet-link defect for one way that
     * happens) used to LATCH 010D off for the rest of the process, and on a car with no GPS fix
     * either, distance then stopped accruing with NO signal anywhere that it had happened - a silent
     * zero in the odometer's own supply line, closed here.
     *
     * A top-level pure function (not nested inside [run]) so it is directly unit-testable without
     * Room, Android, or a running sampling loop.
     */
    internal fun pidWanted(pid: String, failCounts: Map<String, Int>): Boolean =
        pid == "010D" || (failCounts[pid] ?: 0) < MAX_CONSECUTIVE_FAILS

    /**
     * The per-tick GPS-distance acceptance window (ticket 10/03's floor raise - see [MIN_TICK_MILES]'s
     * own doc for the reasoning). `internal` for direct unit testing without Room, Android, or a
     * running sampling loop.
     */
    internal fun gpsTickMilesAccepted(gpsMiles: Double): Boolean = gpsMiles in MIN_TICK_MILES..MAX_TICK_MILES

    /**
     * What a single tick of [run]'s guard should do, given [isBusy] ([ConversationState.isBusy])
     * and [isConnected] ([ObdBluetoothManager.isConnected]) - the exact split the link-loss defect
     * needed (see the class doc). **[isBusy] always wins**, regardless of [isConnected]: a voice
     * turn is never itself evidence a drive ended, so a tick that happens to be both busy AND
     * disconnected is still [TickGuard.SKIP_BUSY], not [TickGuard.SKIP_LINK_LOST] - it must touch
     * no drive-ending counter at all, the same guarantee the old combined guard gave (correctly)
     * for the busy case alone.
     *
     * A top-level pure function (not nested inside [run]) so it is directly unit-testable without
     * Room, Android, Bluetooth, or a running sampling loop - same posture as [pidWanted]/
     * [gpsTickMilesAccepted].
     */
    internal enum class TickGuard { PROCESS, SKIP_BUSY, SKIP_LINK_LOST }

    internal fun tickGuardFor(isBusy: Boolean, isConnected: Boolean): TickGuard = when {
        isBusy -> TickGuard.SKIP_BUSY
        !isConnected -> TickGuard.SKIP_LINK_LOST
        else -> TickGuard.PROCESS
    }

    /**
     * Whether [consecutiveLostTicks] of [TickGuard.SKIP_LINK_LOST] in a row, while a drive was in
     * progress, is enough to trust the link is actually gone rather than a momentary blip - see
     * [LINK_LOST_TICKS]'s own doc for why this threshold is longer than engine-off's
     * [ENGINE_OFF_TICKS]. `internal` for direct unit testing, same posture as [tickGuardFor].
     */
    internal fun linkLostShouldFinalize(consecutiveLostTicks: Int): Boolean =
        consecutiveLostTicks >= LINK_LOST_TICKS

    /** Named identically to [ENGINE_OFF_TICKS]'s own comparison at the [run] call site, so a test
     * can pin the threshold without reading the private constant directly - same posture as
     * [linkLostShouldFinalize]. */
    internal fun engineOffShouldFinalize(consecutiveOffTicks: Int): Boolean =
        consecutiveOffTicks >= ENGINE_OFF_TICKS

    /**
     * Why a drive ended - stored as plain TEXT on [Drive.endReason] (widening this list later needs
     * no migration, CLAUDE.md §5). See the class doc's link-loss note for why [LINK_LOST] did not
     * exist as a reachable outcome before this ticket.
     */
    internal enum class DriveEndReason { ENGINE_OFF, LINK_LOST }

    /** Infinite sampling loop; launch once from the foreground service. */
    suspend fun run(context: Context) {
        val db = CarDatabase.getDatabase(context)
        runCatching { db.odbSampleDao().purgeOlderThan(System.currentTimeMillis() - RETENTION_MS) }
        runCatching { db.carTaskDao().purgeTombstones(System.currentTimeMillis() - TOMBSTONE_HORIZON_MS) }

        val failCounts = mutableMapOf<String, Int>()
        var engineWasOn = false
        var offTicks = 0
        var linkLostTicks = 0
        var lastTickAt = 0L
        var lastLocation: android.location.Location? = null

        while (true) {
            delay(TICK_MS)
            // tickGuardFor's own doc explains why isBusy always wins and touches no drive state -
            // the split this ticket needed (see the class doc's link-loss defect).
            when (tickGuardFor(ConversationState.isBusy, ObdBluetoothManager.isConnected)) {
                TickGuard.SKIP_BUSY -> continue
                TickGuard.SKIP_LINK_LOST -> {
                    if (engineWasOn) {
                        linkLostTicks++
                        if (linkLostShouldFinalize(linkLostTicks)) {
                            finalizeDrive(context, DriveEndReason.LINK_LOST)
                            engineWasOn = false
                            isEngineRunning = false
                            offTicks = 0
                            linkLostTicks = 0
                            lastTickAt = 0L
                            lastLocation = null
                        }
                    }
                    continue
                }
                TickGuard.PROCESS -> Unit
            }
            linkLostTicks = 0

            val rpm = ObdBluetoothManager.getRpm()
            val engineOn = rpm != null && rpm > 0

            if (!engineOn) {
                if (engineWasOn) {
                    offTicks++
                    if (engineOffShouldFinalize(offTicks)) {
                        finalizeDrive(context, DriveEndReason.ENGINE_OFF)
                        engineWasOn = false
                        isEngineRunning = false
                        offTicks = 0
                        linkLostTicks = 0
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
                driveStartedAt = System.currentTimeMillis()
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
            // pidWanted's own doc explains the 010D exemption (ticket 10's latch fix) - kept as a
            // top-level pure function so it's directly unit-testable without Room/Android.
            fun wanted(pid: String) = pidWanted(pid, failCounts)

            add("010C", rpm!!.toDouble(), "rpm")
            if (wanted("0105")) add("0105", ObdBluetoothManager.getCoolantTemp()?.toDouble(), "°C")
            if (wanted("0104")) add("0104", ObdBluetoothManager.getEngineLoad(), "%")
            var maf: Double? = null
            if (wanted("0110")) { maf = ObdBluetoothManager.getMaf(); add("0110", maf, "g/s") }
            var speedKmh: Double? = null
            // Always true post-ticket-10 (see pidWanted's doc) - `if` kept rather than inlined so
            // this call site reads identically to every other PID request above/below it.
            if (wanted("010D")) { speedKmh = ObdBluetoothManager.getSpeedKmh()?.toDouble(); add("010D", speedKmh, "km/h") }
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
            // --- Distance: OBD speed first, GPS as the fallback -------------
            // REVERSED by ticket 10/03 - GPS used to go first. Ticket 03's finding: the dash
            // odometer is itself a PCM speed integration off the exact same VSS PID 010D reads
            // (CCD message 0x84, "PCM TO BCM | INCREMENT MILEAGE"), so 010D is not a worse substitute
            // for GPS here - it puts this estimator and the manual dash reading that resets it
            // (VehicleController.setOdometer) in the SAME reference frame, which GPS never shares.
            //
            // GPS remains the fallback for when 010D has nothing (unsupported PID, or this tick's
            // read failed) - still needed: a head unit with no GPS antenna wired produces NO
            // location at all, and the whole recap/MPG chain hangs off this number. Kevin's XJ
            // (2026-07-16) has no fix; integrating PID 010D closes that with no GPS at all. The
            // mirror-image gap (a 010D-less car) is why GPS has not been removed outright.
            //
            // (2026-07-19) This loop is now also the SINGLE odometer writer:
            // VehicleController.trackTripMileage used to be a separate GPS-only
            // service loop that wrote tripMilesSinceBaseline while this one read
            // the deltas back out - two loops, one dead on a no-GPS car, and the
            // persistent odometer never moved without a fix. The per-tick miles
            // computed here (OBD-or-GPS) now feed BOTH the drive accumulator and
            // the persisted odometer estimate, and the old loop is deleted.
            //
            // Same 30s granularity the MAF fuel integration above already accepts.
            // It undercounts stop-and-go slightly (a tick samples an instant, not
            // an average), the honest trade for a number that exists at all.
            val dtSecDist = if (lastTickAt != 0L) ((now - lastTickAt) / 1000.0).coerceAtMost(MAX_DT_SEC) else 0.0
            var tickMiles = 0.0
            val prevLoc = lastLocation
            if (loc != null) lastLocation = loc
            if (speedKmh != null && speedKmh > 0.0 && dtSecDist > 0.0) {
                tickMiles = speedKmh * dtSecDist / 3600.0 / KM_PER_MILE
            }
            if (tickMiles == 0.0 && loc != null && prevLoc != null) {
                val out = FloatArray(1)
                android.location.Location.distanceBetween(
                    prevLoc.latitude, prevLoc.longitude, loc.latitude, loc.longitude, out,
                )
                val gpsMiles = out[0] / METERS_PER_MILE
                if (gpsTickMilesAccepted(gpsMiles)) tickMiles = gpsMiles
            }
            if (tickMiles > 0.0) {
                // ticket 10 §6, Kevin's ruling: "doing nothing is acceptable, doing nothing
                // silently is not." driveMiles (below) and Vehicle.tripMilesSinceBaseline (via
                // addTripMiles, further below) are TWO SEPARATE accumulators fed by this SAME
                // tickMiles, and they persist through DIFFERENT gates from here on: this one folds
                // in unconditionally whenever tickMiles > 0, while driveMiles only ever becomes a
                // TRIP_MILES sample (which is what DailyDriveLogController's daily-miles rollup
                // actually reads) once finalizeDrive's own MIN_TRIP_MILES/MIN_TRIP_GALLONS gates
                // both clear for the whole finished drive. On Kevin's Jeep that produced one
                // TRIP_MILES row against 938 speed samples - the odometer estimate and the fleet
                // miles sparkline CAN legitimately disagree, and that is left as-is rather than
                // unified or reconciled; this comment is the "said so" half of that ruling.
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

    /**
     * Whether [miles] alone clears the floor worth writing a TRIP_MILES sample for - distance does
     * not depend on fuel math, so this is the ONLY gate TRIP_MILES needs (ticket 09,
     * `.scratch/drive-ui/issues/09-mpg-scale-bug.md`). `internal` for direct unit testing, same
     * posture as [pidWanted]/[gpsTickMilesAccepted].
     */
    internal fun milesWorthRecording(miles: Double): Boolean = miles > MIN_TRIP_MILES

    /**
     * Whether [gallons] alone clears the floor worth trusting as an MPG_TRIP ratio's denominator.
     * MPG_TRIP additionally needs [milesWorthRecording] on the SAME drive (a ratio is meaningless
     * off a near-zero numerator too) - see [finalizeDrive]'s doc for why the two gates are now
     * independent rather than one combined check. `internal` for direct unit testing.
     */
    internal fun gallonsWorthRecording(gallons: Double): Boolean = gallons > MIN_TRIP_GALLONS

    /**
     * What [finalizeDrive] should write for a drive, given whether its accumulated miles/gallons
     * individually cleared [milesWorthRecording]/[gallonsWorthRecording] (ticket 09,
     * `.scratch/drive-ui/issues/09-mpg-scale-bug.md`). `MILES_ONLY` is the whole point of this
     * ticket's split: distance does not depend on fuel math, so a drive with usable miles but no
     * usable gallons (MAF silent or unsupported - see [finalizeDrive]'s own doc for the 166-vs-945
     * sample-count finding this closes) still gets its `TRIP_MILES` row, where the old single
     * combined gate silently wrote NOTHING for it.
     */
    internal enum class TripWrite { NONE, MILES_ONLY, MILES_AND_MPG }

    /** Pure decision behind [TripWrite] - `internal` for direct unit testing, same posture as [pidWanted]/[gpsTickMilesAccepted]. */
    internal fun tripWriteFor(milesOk: Boolean, gallonsOk: Boolean): TripWrite = when {
        milesOk && gallonsOk -> TripWrite.MILES_AND_MPG
        milesOk -> TripWrite.MILES_ONLY
        else -> TripWrite.NONE
    }

    /**
     * What [finalizeDrive] should write into [Drive.gallons] for [decision] - `null`, never `0.0`,
     * on anything short of [TripWrite.MILES_AND_MPG] (see [Drive]'s own doc comment for why `0.0`
     * would be a different, false claim). Factored out as its own pure function, `internal` for
     * direct unit testing, so this specific "unmeasured is null, not zero" property is pinned
     * without Room, Android, or a running sampling loop - same posture as [tripWriteFor].
     */
    internal fun driveGallonsFor(decision: TripWrite, gallons: Double): Double? =
        if (decision == TripWrite.MILES_AND_MPG) gallons else null

    /**
     * Writes the drive's TRIP_MILES/MPG_TRIP summary samples + lifetime aggregates, then resets.
     *
     * **The two writes are gated INDEPENDENTLY** (ticket 09,
     * `.scratch/drive-ui/issues/09-mpg-scale-bug.md`'s "related, and probably the same fix"
     * section, decided via [tripWriteFor]). The old single
     * `if (miles <= MIN_TRIP_MILES || gallons <= MIN_TRIP_GALLONS) return` gated BOTH behind the
     * fuel figure, so a drive where MAF fell silent (or was never supported - [MAX_CONSECUTIVE_FAILS]
     * latches a failing PID off, and MAF, unlike speed, is NOT exempt from that latch - see
     * [pidWanted]'s doc) recorded no distance either, even though distance does not depend on fuel
     * math at all. Across Kevin's Jeep's whole history that produced 166 MAF samples against 945
     * speed samples - most drives silently lost their TRIP_MILES row purely because MPG_TRIP
     * couldn't be computed. `driveMiles`/`driveGallons` are still reset together unconditionally (a
     * drive is over either way, and the accumulators must not bleed into the next one), but each
     * summary sample is now written or withheld on ITS OWN gate.
     *
     * **Also writes one [Drive] row** (`.scratch/drive-ui/issues/05-trip-content.md` Q14) -
     * ADDITIVE to the two `obd_samples` rows above, not a replacement; [DailyDriveLogController]/
     * `MonthlyRecapController` keep reading those. `Drive.gallons` is null, never `0.0`, on a
     * MILES_ONLY drive (same "don't assert an unmeasured quantity" posture as [MpgTrust]) - see
     * [Drive]'s own doc comment. Written on the SAME `decision != NONE` gate as the two samples
     * above, so a drive too short to be worth a TRIP_MILES row is equally not worth a `drives` row.
     */
    private suspend fun finalizeDrive(context: Context, endReason: DriveEndReason) {
        val startedAt = driveStartedAt
        val miles = driveMiles
        val gallons = driveGallons
        driveStartedAt = 0L
        driveMiles = 0.0
        driveGallons = 0.0
        val decision = tripWriteFor(milesWorthRecording(miles), gallonsWorthRecording(gallons))
        if (decision == TripWrite.NONE) return // nothing on either axis worth recording

        runCatching {
            val db = CarDatabase.getDatabase(context)
            val dao = db.odbSampleDao()
            val now = System.currentTimeMillis()
            val lat = LocationController.state.value?.latitude
            val lng = LocationController.state.value?.longitude
            // MPG_TRIP needs BOTH axes - a ratio is meaningless off an unreliable denominator - so
            // it only writes on MILES_AND_MPG, never MILES_ONLY.
            if (decision == TripWrite.MILES_AND_MPG) {
                dao.insert(
                    OdbSample(
                        vehicleId = vehicleId(context),
                        pid = "MPG_TRIP", value = miles / gallons, unit = "mpg",
                        timestamp = now, lat = lat, lng = lng,
                    )
                )
            }
            // TRIP_MILES: the raw per-drive distance MPG_TRIP was computed from (when it could be).
            // Previously only the ratio was kept and this was discarded - added so
            // MonthlyRecapController can sum/count/max real drives for a month (miles driven, drive
            // count, longest drive) without having to reverse-engineer it from the MPG samples.
            // Writes on EITHER non-NONE decision now - distance does not depend on fuel math, so a
            // MILES_ONLY drive still gets its distance recorded.
            dao.insert(
                OdbSample(
                    vehicleId = vehicleId(context),
                    pid = "TRIP_MILES", value = miles, unit = "mi",
                    timestamp = now, lat = lat, lng = lng,
                )
            )
            // The drive-boundary object itself. startedAt falls back to `now` only in the
            // unreachable-in-practice case where a drive accumulated real miles/gallons without
            // driveStartedAt ever being set (defensive only - see driveStartedAt's own doc for why
            // it is always set in the same tick engineWasOn first flips true).
            db.driveDao().insert(
                Drive(
                    vehicleId = vehicleId(context),
                    startedAt = if (startedAt != 0L) startedAt else now,
                    endedAt = now,
                    miles = miles,
                    gallons = driveGallonsFor(decision, gallons),
                    endReason = endReason.name,
                )
            )
        }
        // Lifetime gal/mi aggregates (SharedPreferences, read by lifetimeMpg): only meaningful
        // together, so still folded in only on MILES_AND_MPG - a MILES_ONLY drive contributes
        // nothing here, same as before this ticket, and correctly so: adding its miles alone would
        // silently deflate the lifetime mpg denominator's partner.
        if (decision == TripWrite.MILES_AND_MPG) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = vehicleId(context)
            p.edit()
                .putFloat("${id}_gal", p.getFloat("${id}_gal", 0f) + gallons.toFloat())
                .putFloat("${id}_mi", p.getFloat("${id}_mi", 0f) + miles.toFloat())
                .apply()
        }
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
