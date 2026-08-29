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
 * A `Vehicle` row is co-owned: the household's Supabase project owns identity/specs (mirrored by
 * [VehicleReplica]), and THIS phone owns [personaPrompt]/[voiceName]/[personaTraits]/[archived]/
 * [onboarded]/[lastOdometerPromptAt]/[tripMilesSinceBaseline] - measured live by grep across `ui/`
 * and `vehicle/` (ticket 14: `archived` read 15 times, `tripMilesSinceBaseline` 5, `onboarded` 3),
 * and kept off the server on purpose (ticket 01 ruling 10). A configured read composes
 * [VehicleReplica] (server-owned columns) with this table (phone-owned columns) - see
 * [com.kevin.legion.vehicle.FleetEngineStore]'s own class doc for where that composition happens.
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
 * phone-only by design (never reconciled against a server value), and dropping `vehicles` from
 * [com.kevin.legion.sync.SyncEngine]'s registry in this same ticket retires the old Drive-based
 * cross-phone channel that used to carry them - see that registry's own comment on the `"vehicles"`
 * entry for the full account of what is and is not still true post-cutover. A real consequence,
 * not a silent one: archived/persona/trip-miles state is now per-DEVICE on a configured install,
 * where it used to be per-USER (synced across Kevin's two phones via Drive). If that is ever
 * unwanted, it is this table (and the registry drop) that would need to grow a real sync channel,
 * not a reason to leave `vehicles` in the registry pointing at a table this cutover is replacing.
 */
@Entity(
    tableName = "vehicle_sidecar",
    indices = [Index("obdMac", unique = true)],
)
data class VehicleSidecar(
    @PrimaryKey val serverId: String,
    val obdMac: String,
    val personaPrompt: String = "",
    val voiceName: String = "",
    val personaTraits: String = "",
    val archived: Boolean = false,
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

    @Query("UPDATE vehicle_sidecar SET archived = :archived WHERE serverId = :serverId")
    suspend fun setArchived(serverId: String, archived: Boolean)

    @Query("UPDATE vehicle_sidecar SET lastOdometerPromptAt = :at WHERE serverId = :serverId")
    suspend fun markOdometerPrompted(serverId: String, at: Long)

    @Query("UPDATE vehicle_sidecar SET tripMilesSinceBaseline = tripMilesSinceBaseline + :delta WHERE serverId = :serverId")
    suspend fun addTripMiles(serverId: String, delta: Double)

    @Query("UPDATE vehicle_sidecar SET tripMilesSinceBaseline = 0.0 WHERE serverId = :serverId")
    suspend fun resetTripMiles(serverId: String)
}
