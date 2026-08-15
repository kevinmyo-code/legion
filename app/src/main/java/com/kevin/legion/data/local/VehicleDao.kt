package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for [Vehicle].
 */
@Dao
interface VehicleDao {
    /**
     * REPLACES THE WHOLE ROW. This must only be used to CREATE a row - a stale
     * in-memory [Vehicle] passed here clobbers every column a concurrent writer
     * owns, not just the one the caller meant to touch (ticket 13,
     * `.scratch/fleet-maintenance/issues/13-the-jeep-row-lost-its-identity.md`:
     * a whole-row upsert from a seeded placeholder is how a real car's
     * make/model/year/odometer/onboarded state got overwritten back to blank on
     * Kevin's phone). Every writer that owns only some of a vehicle's columns
     * must use one of the targeted `@Query` updates below instead - see each
     * one's doc for which columns it touches.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStamped(vehicle: Vehicle)

    /**
     * Every vehicle write goes through here so [Vehicle.updatedAt] is refreshed for
     * cross-device sync last-write-wins (S1) - a copy loaded-then-edited would keep
     * its old stamp otherwise, and LWW couldn't tell which device is newer.
     *
     * Same REPLACE-the-whole-row warning as [upsertStamped]: only call this to
     * create a genuinely new row (a fresh dongle MAC or synthetic car-profile id
     * that has never had a row before). An existing row must be edited through a
     * targeted `@Query` update, not through this.
     */
    suspend fun upsert(vehicle: Vehicle) =
        upsertStamped(vehicle.copy(updatedAt = System.currentTimeMillis()))

    /**
     * Records that Aria just asked for an odometer update (touches
     * [Vehicle.lastOdometerPromptAt] only), so a concurrent writer's edit to
     * any other column (odometer, identity, trip miles) can never be lost to
     * this call the way a whole-row [upsert] could.
     */
    @Query("UPDATE vehicles SET lastOdometerPromptAt = :at, updatedAt = :now WHERE obdMac = :mac")
    suspend fun markOdometerPrompted(mac: String, at: Long, now: Long)

    /**
     * Flips [Vehicle.onboarded] on once the one-time maintenance-interval
     * lookup has populated a car's schedule. Touches only that column (plus the
     * LWW stamp) - see [upsertStamped]'s doc for why a whole-row write here was
     * the wrong shape.
     */
    @Query("UPDATE vehicles SET onboarded = 1, updatedAt = :now WHERE obdMac = :mac")
    suspend fun markOnboarded(mac: String, now: Long)

    /**
     * Accumulates [Vehicle.tripMilesSinceBaseline] IN SQL rather than
     * read-modify-write in Kotlin, which removes a real race: the old shape
     * (`vehicle.copy(tripMilesSinceBaseline = vehicle.tripMilesSinceBaseline +
     * delta)` then whole-row [upsert]) read a possibly-stale in-memory
     * [Vehicle] every 30 seconds from [com.kevin.legion.vehicle.TelemetryRecorder.run] -
     * the highest-frequency writer of this row in the app - and clobbered
     * whatever any other writer had changed in between the read and the write.
     * `tripMilesSinceBaseline = tripMilesSinceBaseline + :delta` lets SQLite do
     * the addition against the row's current value at write time, so there is
     * no window for a lost update.
     *
     * No-ops (0 rows affected) if [mac] has no row yet - by design, per ticket
     * 13: telemetry for a car nobody has registered must not manufacture one.
     */
    @Query("UPDATE vehicles SET tripMilesSinceBaseline = tripMilesSinceBaseline + :delta, updatedAt = :now WHERE obdMac = :mac")
    suspend fun addTripMiles(mac: String, delta: Double, now: Long)

    /**
     * Records a driver-reported odometer reading as a fresh baseline: the
     * driver is the source of truth here, so the trip accumulator resets to
     * zero and the "ask again" clock ([Vehicle.lastOdometerPromptAt]) restarts
     * from the same moment the reading was given, exactly mirroring the fields
     * the old whole-row [VehicleController.setOdometer] write touched - just
     * without touching identity, persona, or archive state along the way.
     *
     * **Returns the number of rows affected, and the caller MUST check it.**
     * Since ticket 13 stopped [com.kevin.legion.vehicle.VehicleController.seedVehicle]
     * persisting placeholders, [mac] can legitimately name a car that has no row
     * on file - and a targeted UPDATE against a missing row succeeds at the SQL
     * level while writing nothing. Reporting that as "Got it, 142,500 on the
     * clock" would be a false success of exactly the shape this ticket exists to
     * remove (lessons.md L16), so a `0` here is an error to surface, never a
     * result to shrug at.
     */
    @Query(
        "UPDATE vehicles SET odometerBaseline = :miles, odometerBaselineAt = :at, " +
            "tripMilesSinceBaseline = 0.0, lastOdometerPromptAt = :at, updatedAt = :now " +
            "WHERE obdMac = :mac"
    )
    suspend fun setOdometerBaseline(mac: String, miles: Int, at: Long, now: Long): Int

    /**
     * Corrects a car's stated identity (year/make/model/trim/name) and marks it
     * [Vehicle.confirmed] - the repair path for a row that got the wrong badge,
     * or the write-back for a first-time registration. Touches identity columns
     * only: odometer, persona, voiceName/personaTraits, archived and
     * lastOdometerPromptAt all ride along untouched, which is the fix for
     * [com.kevin.legion.vehicle.VehicleController.registerDirect]'s silent
     * field-drop (ticket 13) - it used to rebuild a fresh [Vehicle] from
     * scratch and lose every field it didn't explicitly carry forward.
     */
    @Query(
        "UPDATE vehicles SET year = :year, make = :make, model = :model, trim = :trim, " +
            "name = :name, confirmed = 1, updatedAt = :now WHERE obdMac = :mac"
    )
    suspend fun setIdentity(mac: String, year: Int, make: String, model: String, trim: String, name: String, now: Long)

    /**
     * Fills in identity columns from a vPIC VIN decode (year/make/model/trim) - the write-back
     * ticket 04 names (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`,
     * `13-the-jeep-row-lost-its-identity.md`). Touches [Vehicle.name] and [Vehicle.confirmed]
     * NOWHERE in this query - deliberately NOT [setIdentity], which always stamps
     * `confirmed = 1`. That is correct for a DRIVER stating the car's identity (voice register,
     * manual correction) but wrong for a vPIC lookup: [com.kevin.legion.vehicle.VinDecoder]'s own
     * class doc says a decode is "a confirmable suggestion, never a silent overwrite", and
     * [Vehicle.confirmed] gates recall lookups and the label surfaces ticket 04 is about on the
     * DRIVER having actually said so - a decode filling in blanks on the driver's behalf must not
     * silently claim that consent too.
     *
     * Every value passed here is the caller's ALREADY-DECIDED final value for that column (decoded
     * where blank, left as the existing value otherwise) - see
     * [com.kevin.legion.vehicle.VehicleController.applyDecodedIdentity], which computes them
     * per-field before this query ever runs and never calls this at all when nothing changed.
     *
     * **Returns the number of rows affected, and the caller MUST check it** - same false-success
     * shape [setOdometerBaseline]'s doc warns about: a targeted UPDATE against a vehicleId with no
     * row (a `vehicle_specs` row can now exist with no matching `vehicles` row - ticket 04's
     * "orphan-to-parent" note) succeeds at the SQL level while writing nothing.
     */
    @Query(
        "UPDATE vehicles SET year = :year, make = :make, model = :model, trim = :trim, " +
            "updatedAt = :now WHERE obdMac = :mac"
    )
    suspend fun applyDecodedIdentity(mac: String, year: Int, make: String, model: String, trim: String, now: Long): Int

    /**
     * Hides/unhides a car from the roster and picker (touches [Vehicle.archived]
     * only). Replaces a read-then-whole-row-write round trip in
     * [com.kevin.legion.vehicle.VehicleController]'s `setArchived` that could
     * otherwise clobber a concurrent edit to any other column between the read
     * and the write.
     */
    @Query("UPDATE vehicles SET archived = :archived, updatedAt = :now WHERE obdMac = :mac")
    suspend fun setArchived(mac: String, archived: Boolean, now: Long)

    @Query("SELECT * FROM vehicles WHERE obdMac = :mac")
    suspend fun getByMac(mac: String): Vehicle?

    /** Active cars only - archived ones are hidden from the roster and picker. */
    @Query("SELECT * FROM vehicles WHERE archived = 0")
    suspend fun getAll(): List<Vehicle>

    /** Every car including archived, for the roster's "Show archived" toggle. */
    @Query("SELECT * FROM vehicles")
    suspend fun getAllIncludingArchived(): List<Vehicle>

    /**
     * Every vehicle row. Exists so a test can isolate itself: Robolectric gives
     * a fresh sandbox per test CLASS, not per method, so rows seeded by one
     * method survive into the next and a roster assertion reads more cars than
     * it seeded. `Room.clearAllTables()` is the obvious alternative and is worse
     * here - it is a blocking main-thread call that also fights the open
     * connection for the write lock (both observed, 2026-08-07). A suspend DAO
     * delete runs on Room's own executor and does neither.
     *
     * No production caller. If one ever appears, it should go through a
     * controller that also handles the per-device active-vehicle selection.
     */
    @Query("DELETE FROM vehicles")
    suspend fun deleteAll()
}
