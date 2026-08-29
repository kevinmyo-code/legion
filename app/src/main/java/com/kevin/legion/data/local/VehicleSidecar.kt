package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The local half of a co-owned `vehicles` row (backend-erp ticket 26,
 * `.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`, resolving ticket 14's option 1).
 *
 * **CORRECTED 2026-08-29, ticket 27
 * (`.scratch/backend-erp/issues/27-the-sidecar-has-no-cross-device-channel.md`, "RULED 2026-08-29").**
 * This table used to carry seven columns. Four of them were wrong to carry here and have moved on:
 *
 * - `personaPrompt`/`voiceName`/`personaTraits` are gone from this table entirely, not relocated.
 *   Traced every reader: the only consumer was this same sidecar's own copy-through, and nothing
 *   downstream ever read the copy - `LiveSessionController` gets its voice from
 *   `CompanionProfile.voice(appContext)` at every socket-open site, never from a `Vehicle` field.
 *   They are leftovers from the per-car identity model CLAUDE.md section 2 killed ("Cars are data,
 *   not identities"). Still present, unread, on the legacy [Vehicle] entity - see that class's own
 *   doc comment - because CLAUDE.md section 5 is additive-migrations-only and a dead column costs
 *   nothing where it already sits.
 * - `archived` moved to the server (`public.vehicles.archived`, carried on [VehicleReplica]) - it is
 *   USER state, not device state: a car Kevin retired is retired everywhere, the same reasoning
 *   `public.vehicles` already applies to every other identity field.
 *
 * **This table now keeps exactly three columns, and all three are genuinely per-device:**
 *
 * - [onboarded] - whether THIS phone has already run its one-time maintenance-interval populate
 *   for this car. A statement about what this install has already asked, not about the car.
 * - [lastOdometerPromptAt] - when THIS phone last nagged for an odometer reading, so the monthly
 *   check-in cadence is per-install rather than shared (a value that would otherwise suppress the
 *   nag on a second phone that never actually asked).
 * - [tripMilesSinceBaseline] - accumulates from whichever phone's OBD dongle is actually in the
 *   car right now (`vehicle/TelemetryRecorder.kt`'s live tick). It means "since baseline, as
 *   observed by this device's dongle session" and has no coherent cross-device value to reconcile
 *   against - two phones summing their own accumulations would double-count the same drive.
 *
 * A configured read composes [VehicleReplica] (server-owned columns, including `archived`) with
 * this table (phone-owned columns) - see [com.kevin.legion.vehicle.FleetEngineStore]'s own class
 * doc for where that composition happens.
 *
 * **Keyed on [serverId], not [obdMac]** - ticket 26's own ruling: "the phone-only columns live in
 * a local sidecar keyed on the same serverId, and a configured read composes replica + sidecar."
 * [obdMac] rides along as a plain (unique, non-primary) column purely so the CONFIGURED path can
 * still answer "which sidecar row belongs to the car this dongle just connected to" - the same
 * question [Vehicle.obdMac] answers on the unconfigured path, where [Vehicle] itself is still the
 * only store and this table does not exist for that car at all. **[obdMac] is never sent to the
 * server** - ticket 26's own ruling 14: "It is a MAC address, and a car can change dongles."
 *
 * **This table intentionally has no `updatedAtMs`/sync machinery of its own.** These columns are
 * phone-only by design (never reconciled against a server value), and `vehicles` was dropped from
 * [com.kevin.legion.sync.SyncEngine]'s registry at ticket 26 - see that registry's own comment on
 * the `"vehicles"` entry for the full account of what is and is not still true post-cutover.
 * `onboarded`/`lastOdometerPromptAt`/`tripMilesSinceBaseline` were never meant to agree across two
 * phones in the first place (unlike `archived`, which is why that one got a real server column
 * instead of staying here), so losing their old Drive-based channel is not a regression - see
 * ticket 27's own "RULED" section for the reasoning that separated the three that stayed here from
 * the four that left.
 */
@Entity(
    tableName = "vehicle_sidecar",
    indices = [Index("obdMac", unique = true)],
)
data class VehicleSidecar(
    @PrimaryKey val serverId: String,
    val obdMac: String,
    val onboarded: Boolean = false,
    val lastOdometerPromptAt: Long = 0L,
    val tripMilesSinceBaseline: Double = 0.0,
)

/**
 * Every write here is a targeted `@Query`, matching [VehicleDao]'s own "no accidental whole-row
 * clobber" discipline - see that interface's [VehicleDao.upsertStamped] doc comment for the
 * incident (ticket 13) this posture exists to never repeat.
 */
@Dao
interface VehicleSidecarDao {
    @Query("SELECT * FROM vehicle_sidecar WHERE serverId = :serverId")
    suspend fun getByServerId(serverId: String): VehicleSidecar?

    @Query("SELECT * FROM vehicle_sidecar WHERE obdMac = :obdMac")
    suspend fun getByMac(obdMac: String): VehicleSidecar?

    /** Create-or-replace-wholesale - only ever called with a row already fully specified from the
     * current legacy [Vehicle] row's own phone-only columns (see
     * [com.kevin.legion.vehicle.FleetEngineStore]'s `syncVehicleToServer`), so a whole-row REPLACE
     * here carries no risk of dropping a field an in-flight concurrent writer owns - unlike
     * [VehicleDao.upsertStamped]'s warning, there is exactly one writer of this table's phone-only
     * columns (this same sidecar sync path), never a second one racing it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: VehicleSidecar)

    @Query("UPDATE vehicle_sidecar SET lastOdometerPromptAt = :at WHERE serverId = :serverId")
    suspend fun markOdometerPrompted(serverId: String, at: Long)

    @Query("UPDATE vehicle_sidecar SET tripMilesSinceBaseline = tripMilesSinceBaseline + :delta WHERE serverId = :serverId")
    suspend fun addTripMiles(serverId: String, delta: Double)

    @Query("UPDATE vehicle_sidecar SET tripMilesSinceBaseline = 0.0 WHERE serverId = :serverId")
    suspend fun resetTripMiles(serverId: String)
}
